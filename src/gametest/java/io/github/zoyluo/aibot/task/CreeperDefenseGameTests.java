package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;

/**
 * Black-box safety contracts for the dedicated Creeper owner.
 *
 * <p>These tests intentionally use real survival inventory, entity observation, movement and
 * block placement. Task descriptions expose only the public diagnostic state needed to distinguish
 * ESCAPE, BUILD_WALL and HOLD_BARRIER; no test-only production hook is required.</p>
 */
public final class CreeperDefenseGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 40)
    public void lateFuseAssignmentStartsPhysicalDefenseSynchronously(TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperLateFuseGT", 200);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 8));
        assertStrictCapabilities(context, bot);

        HoldingTask mission = new HoldingTask("late_fuse_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_late_fuse_mission"));
        CreeperEntity creeper = spawnDisabledCreeper(context, origin.east(2));
        creeper.ignite();
        creeper.setFuseSpeed(1);
        for (int tick = 0; tick < 20; tick++) {
            creeper.tick();
        }
        require(context, creeper.isAlive()
                        && creeper.isIgnited()
                        && creeper.getFuseSpeed() > 0
                        && creeper.getClientFuseTime(1.0F) > 0.0F,
                "late-fuse fixture did not retain a live finite explosion clock");

        Vec3d before = bot.getPos();
        int materialBefore = MaterialPalette.countEmergencyShelterBlocks(bot);
        require(context, DangerWatcher.INSTANCE.scanBot(
                        context.getWorld().getServer(), bot),
                "late-fuse Creeper scan was not handled");

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask
                        && TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "late-fuse scan did not assign the dedicated SAFETY owner");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "late-fuse scan did not preserve exactly one mission frame");

        boolean physicallyStepped = bot.getPos().squaredDistanceTo(before) >= 0.80D;
        boolean physicallyPlacedCore = MaterialPalette.countEmergencyShelterBlocks(bot)
                < materialBefore && hasPlacedOakLogNear(bot, origin);
        require(context, physicallyStepped || physicallyPlacedCore,
                "late-fuse onStart only scheduled a future path; no synchronous physical"
                        + " fast-step or core-wall action occurred: " + active.describe());
        finish(context, bot, "CreeperLateFuseGT", creeper);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 40)
    public void hiddenNearMemoryIsNotOverwrittenByFarUnarmedCreeper(TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperMemoryGT", 206);
        BlockPos origin = bot.getBlockPos().toImmutable();
        assertStrictCapabilities(context, bot);

        HoldingTask mission = new HoldingTask("creeper_memory_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_memory"));
        CreeperEntity near = spawnDisabledCreeper(context, origin.east(3));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "near Creeper did not assign the dedicated owner");
        String nearSource = compact(near.getBlockPos());
        require(context, active.describe().contains("source=" + nearSource),
                "owner did not bind the initially observed near source: " + active.describe());

        bot.getActionPack().stopAll();
        bot.teleport(context.getWorld(), origin.getX() + 0.5D, origin.getY(),
                origin.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        buildOccludingWall(context, origin.east(), 1);
        require(context, !ObservableWorldQuery.canObserveEntity(bot, near),
                "near Creeper remained observable through the memory occluder");
        CreeperEntity far = spawnDisabledCreeper(context, origin.west(5));
        require(context, ObservableWorldQuery.canObserveEntity(bot, far)
                        && !far.isIgnited() && far.getFuseSpeed() <= 0,
                "far replacement fixture was not a visible unarmed Creeper");

        active.tick(bot);
        require(context, active.describe().contains("source=" + nearSource),
                "visible unarmed far Creeper overwrote the more dangerous hidden near memory:"
                        + " near=" + nearSource + " far=" + compact(far.getBlockPos())
                        + " owner=" + active.describe());
        finish(context, bot, "CreeperMemoryGT", near, far);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 40)
    public void olderOccludedRiskCannotCompleteWhileSecondRiskJustTurnedHidden(
            TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperAllRiskGraceGT", 236);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        assertStrictCapabilities(context, bot);

        CreeperEntity older = spawnDisabledCreeper(context, origin.east(3));
        older.ignite();
        older.setFuseSpeed(1);
        for (int tick = 0; tick < 20; tick++) {
            older.tick();
        }
        CreeperDefenseTask owner = new CreeperDefenseTask(
                older.getUuid(), older.getBlockPos());
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.safety("gametest_all_risk_grace"));
        require(context, phase(owner, "HOLD_BARRIER"),
                "late older risk did not synchronously establish its owned core: "
                        + owner.describe());
        older.discard();

        for (int tick = 0; tick < 98; tick++) {
            owner.tick(bot);
        }
        require(context, owner.state() == TaskState.RUNNING,
                "older risk completed before its own observation grace");

        CreeperEntity newer = spawnDisabledCreeper(context, origin.west(5));
        require(context, ObservableWorldQuery.canObserveEntity(bot, newer),
                "second-risk fixture was not factually observable");
        owner.tick(bot);
        newer.discard();
        owner.tick(bot);

        require(context, owner.elapsedTicks() == 100
                        && owner.state() == TaskState.RUNNING
                        && phase(owner, "HOLD_BARRIER"),
                "owner used the older wall/grace to release a second risk hidden for one tick: "
                        + owner.describe() + " state=" + owner.state());

        BlockPos allRiskClearance = origin.south(12);
        makeStandable(context, allRiskClearance);
        for (int tick = 0; tick < 100 && owner.state() == TaskState.RUNNING; tick++) {
            bot.getActionPack().stopAll();
            bot.teleport(context.getWorld(),
                    allRiskClearance.getX() + 0.5D,
                    allRiskClearance.getY(),
                    allRiskClearance.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            bot.setVelocity(Vec3d.ZERO);
            owner.tick(bot);
        }
        require(context, owner.state() == TaskState.COMPLETED,
                "all-risk grace fix retained ownership after every remembered source had"
                        + " independently reached grace and physical clearance: "
                        + owner.describe() + " state=" + owner.state());
        finish(context, bot, "CreeperAllRiskGraceGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 60)
    public void lateralOscillationCannotResetAwayProgress(TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperLateralStallGT", 212);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 6));
        assertStrictCapabilities(context, bot);
        CreeperEntity creeper = spawnDisabledCreeper(context, origin.east(6));
        CreeperDefenseTask owner = new CreeperDefenseTask(
                creeper.getUuid(), creeper.getBlockPos());
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.safety("gametest_lateral_stall"));

        for (int tick = 0; tick < 8 && phase(owner, "ESCAPE"); tick++) {
            double lateral = tick % 2 == 0 ? 0.10D : 0.90D;
            bot.teleport(context.getWorld(), origin.getX() + 0.5D, origin.getY(),
                    origin.getZ() + lateral, Set.of(), 0.0F, 0.0F, true);
            bot.setVelocity(Vec3d.ZERO);
            owner.tick(bot);
        }

        require(context, !phase(owner, "ESCAPE") && owner.elapsedTicks() <= 6,
                "north/south oscillation reset away-progress and suppressed the five-tick"
                        + " core-wall escalation: " + owner.describe());
        owner.tick(bot);
        owner.tick(bot);
        BlockPos center = origin;
        require(context, isPhysicalBarrierCell(bot, center)
                        && isPhysicalBarrierCell(bot, center.up()),
                "lateral-stall escalation did not physically complete its central two-high wall:"
                        + " bot=" + bot.getBlockPos().toShortString()
                        + " owner=" + owner.describe());
        finish(context, bot, "CreeperLateralStallGT", creeper);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 60)
    public void twoLegalBlocksCompleteCoreAndHoldWithoutSideMaterial(TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperTwoBlockCoreGT", 218);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        assertStrictCapabilities(context, bot);
        CreeperEntity creeper = spawnDisabledCreeper(context, origin.east(3));
        creeper.ignite();
        CreeperDefenseTask owner = new CreeperDefenseTask(
                creeper.getUuid(), creeper.getBlockPos());
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.safety("gametest_two_block_core"));

        owner.tick(bot);
        owner.tick(bot);
        require(context, isPhysicalBarrierCell(bot, origin)
                        && isPhysicalBarrierCell(bot, origin.up()),
                "two legal blocks did not become the central two-high physical wall: "
                        + owner.describe());
        require(context, MaterialPalette.countEmergencyShelterBlocks(bot) == 0,
                "two-block fixture retained unexpected side-wall material");

        creeper.discard();
        owner.tick(bot);
        owner.tick(bot);
        require(context, owner.state() == TaskState.RUNNING
                        && phase(owner, "HOLD_BARRIER"),
                "missing optional side material discarded a proven central wall instead of"
                        + " retaining HOLD_BARRIER ownership: " + owner.describe()
                        + " state=" + owner.state() + ":" + owner.failureReason());
        require(context, isPhysicalBarrierCell(bot, origin)
                        && isPhysicalBarrierCell(bot, origin.up()),
                "central wall was not maintained during the hidden-pressure hold");
        finish(context, bot, "CreeperTwoBlockCoreGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 60)
    public void hiddenCreeperMemoryYieldsToNonCreeperLowHpShelter(TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperShelterHandoffGT", 224);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 32));
        assertStrictCapabilities(context, bot);

        HoldingTask mission = new HoldingTask("creeper_shelter_handoff_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_shelter_handoff"));
        // Five blocks is still factual Creeper pressure, but stays outside the synchronous
        // four-block core-wall boundary so this fixture tests scheduler handoff rather than
        // deliberately teleporting back into an owned placement cell.
        CreeperEntity creeper = spawnDisabledCreeper(context, origin.east(5));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task creeperOwner = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, creeperOwner instanceof CreeperDefenseTask
                        && mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fixture did not establish one Creeper SAFETY owner over one mission frame");

        bot.getActionPack().stopAll();
        bot.teleport(context.getWorld(), origin.getX() + 0.5D, origin.getY(),
                origin.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        bot.fallDistance = 0.0F;
        bot.setOnGround(true);
        buildOccludingWall(context, origin.east(2), 1);
        require(context, !ObservableWorldQuery.canObserveEntity(bot, creeper),
                "Creeper was not hidden while its owner retained last-seen memory");

        ZombieEntity zombie = spawnDisabledZombie(context, origin.west(3));
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        require(context, ObservableWorldQuery.canObserveEntity(bot, zombie)
                        && CombatCore.hasLineOfSight(bot, zombie),
                "non-Creeper LOW_HP fixture was not factual visible pressure");
        require(context, EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "handoff fixture could not admit a physical emergency shelter");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EmergencyShelterTask
                        && TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "hidden Creeper memory blocked a live non-Creeper LOW_HP shelter handoff: "
                        + (active == null ? "idle" : active.name() + "/" + active.describe()));
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "SAFETY-to-SAFETY shelter handoff duplicated or replaced the mission frame");
        finish(context, bot, "CreeperShelterHandoffGT", creeper, zombie);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "creeperDefenseSafety", tickLimit = 180)
    public void nonSafetyEatIsPausedAndResumedAsExactInstance(TestContext context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperEatResumeGT", 230);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));
        bot.getHungerManager().setFoodLevel(17);
        assertStrictCapabilities(context, bot);

        EatTask eat = new EatTask();
        TaskManager.INSTANCE.assign(bot, eat,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_non_safety_eat"));
        CreeperEntity creeper = spawnDisabledCreeper(context, origin.east(4));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "Creeper did not replace the non-SAFETY EatTask with its dedicated owner");
        require(context, eat.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == eat
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "non-SAFETY EatTask was aborted or copied instead of pausing its exact instance:"
                        + " eat=" + eat.state() + " depth="
                        + TaskManager.INSTANCE.pausedDepth(bot));

        CreeperDefenseTask owner = (CreeperDefenseTask) active;
        creeper.discard();
        BlockPos clearance = origin.west(12);
        makeStandable(context, clearance);
        bot.getActionPack().stopAll();
        bot.teleport(context.getWorld(), clearance.getX() + 0.5D, clearance.getY(),
                clearance.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        for (int tick = 0; tick < 105 && owner.state() == TaskState.RUNNING; tick++) {
            owner.tick(bot);
        }
        require(context, owner.state() == TaskState.COMPLETED,
                "cleared Creeper owner did not settle after its bounded hidden-source grace: "
                        + owner.describe() + " state=" + owner.state());
        TaskManager.INSTANCE.tickAll(context.getWorld().getServer());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == eat
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "completed Creeper owner did not expose the exact paused EatTask");

        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.hurtTime = 0;
        bot.getActionPack().stopAll();
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == eat
                        && eat.state() == TaskState.RUNNING
                        && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                "clear pressure did not resume the exact non-SAFETY EatTask instance: "
                        + " active=" + TaskManager.INSTANCE.getActive(bot)
                        .map(Task::name).orElse("idle")
                        + " eat=" + eat.state()
                        + " depth=" + TaskManager.INSTANCE.pausedDepth(bot));
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(originValue -> originValue.kind() == TaskOrigin.Kind.VERIFY)
                        .orElse(false),
                "resumed EatTask lost its original non-SAFETY origin");
        finish(context, bot, "CreeperEatResumeGT");
    }

    private static AIPlayerEntity spawnArenaBot(TestContext context,
                                                 String name,
                                                 int relativeY) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, relativeY, 4));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(
                        cell.down(), Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        return bot;
    }

    private static CreeperEntity spawnDisabledCreeper(TestContext context, BlockPos feet) {
        CreeperEntity creeper = EntityType.CREEPER.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (creeper == null) {
            throw new IllegalStateException("failed to create Creeper fixture");
        }
        makeStandable(context, feet);
        creeper.setPersistent();
        creeper.setAiDisabled(true);
        creeper.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(creeper),
                "failed to spawn Creeper fixture");
        return creeper;
    }

    private static ZombieEntity spawnDisabledZombie(TestContext context, BlockPos feet) {
        ZombieEntity zombie = EntityType.ZOMBIE.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            throw new IllegalStateException("failed to create Zombie fixture");
        }
        makeStandable(context, feet);
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        zombie.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(zombie),
                "failed to spawn Zombie fixture");
        return zombie;
    }

    private static void makeStandable(TestContext context, BlockPos feet) {
        context.getWorld().setBlockState(
                feet.down(), Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                feet.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void buildOccludingWall(TestContext context,
                                            BlockPos center,
                                            int halfWidth) {
        for (int dz = -halfWidth; dz <= halfWidth; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                context.getWorld().setBlockState(
                        center.add(0, dy, dz),
                        Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static boolean hasPlacedOakLogNear(AIPlayerEntity bot, BlockPos origin) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (bot.getServerWorld().getBlockState(
                            origin.add(dx, dy, dz)).isOf(Blocks.OAK_LOG)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isPhysicalBarrierCell(AIPlayerEntity bot, BlockPos pos) {
        var state = bot.getServerWorld().getBlockState(pos);
        return !state.getCollisionShape(bot.getServerWorld(), pos).isEmpty();
    }

    private static boolean phase(Task task, String expected) {
        return task.describe().contains("phase=" + expected);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void assertStrictCapabilities(TestContext context, AIPlayerEntity bot) {
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "creeper_defense_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static void finish(TestContext context,
                               AIPlayerEntity bot,
                               String name,
                               Entity... entities) {
        for (Entity entity : entities) {
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
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
            return "Holding exact resumable mission cursor";
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
