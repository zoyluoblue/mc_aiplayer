package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningFoodReserve;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Live fail-closed coverage for underground tool and safe-food service. */
public final class MiningServiceResourceGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceAdmissionVelocityStrict", tickLimit = 120)
    public void disposalAdmissionCentersResidualOreWalkBeforePublishingOpenDebt(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceAdmissionVelocityGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "admission-velocity", 0, cursor);

        // Reproduce the sealed evidence pose: OreDig has reached the correct BlockPos but its
        // physical walk ended on the forward edge with enough residual velocity to cross into the
        // next cell on the following entity tick.
        bot.teleport(bot.getServerWorld(), face.getX() + 0.5D, face.getY(),
                face.getZ() + 0.95D, Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        bot.setVelocity(0.0D, 0.0D, 0.85D);
        task.start(bot);
        task.tick(bot);
        require(context, "OPEN_DISPOSAL_POCKET".equals(task.checkpoint().get("phase")),
                "service published no OPEN transaction after admission centering: "
                        + task.checkpoint());
        require(context, bot.getBlockPos().equals(face)
                        && bot.getVelocity().lengthSquared() == 0.0D,
                "OPEN transaction retained the prior ore-walk motion state");

        AtomicReference<Integer> observedTicks = new AtomicReference<>(0);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            require(context, task.state() != TaskState.FAILED,
                    "admitted disposal failed after residual walk: " + task.failureReason());
            require(context, bot.getBlockPos().equals(face),
                    "admitted disposal drifted off work face: "
                            + bot.getBlockPos().toShortString());
            require(context, !live.getOrDefault("pocket_failure", "")
                            .contains("geometry_anchor_changed")
                            && !"RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))
                            && !"SEAL_DISPOSAL_POCKET".equals(live.get("phase")),
                    "pre-OPEN motion became durable geometry debt: " + live);
            int ticks = observedTicks.get() + 1;
            observedTicks.set(ticks);
            if (ticks < 8) {
                return;
            }
            task.abort(bot);
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceNaturalPocketSealStrict", tickLimit = 320)
    public void naturalOpenPocketWithoutHeadSupportSealsFloorFirst(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceNaturalPocketSealGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        // Control the two-block spoil stack directly. Vanilla opening drops may reach the player
        // zero, one, or two at a time depending on entity motion, which obscures the selector this
        // regression is intended to lock.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLED_DEEPSLATE, 2));
        for (int index = 0; index < 28; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "natural-pocket fixture did not require a capacity handoff");

        BlockPos face = bot.getBlockPos().toImmutable();
        Direction direction = Direction.EAST;
        BlockPos entry = face.offset(direction);
        BlockPos sink = face.offset(direction, 2);
        var world = bot.getServerWorld();
        // Reproduce the seed-3000 coal-vein cavity: the mouth and sink are already open, their
        // floors and far wall are sound, but the head mouth has no persistent adjacent support.
        // A legal seal must therefore place the feet block against the floor before placing the
        // head block against the new feet block.
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up(),
                entry.up(2), entry.north(), entry.north().up(),
                entry.south(), entry.south().up()}) {
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(entry.down(),
                Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink.down(),
                Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink.offset(direction),
                Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink.offset(direction).up(),
                Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            world.setBlockState(sink.offset(side),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(sink.offset(side).up(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (Direction neighbor : Direction.values()) {
            require(context, world.getBlockState(entry.up().offset(neighbor)).isAir(),
                    "natural-pocket head unexpectedly began with placement support at "
                            + neighbor.asString());
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.capacityHandoff(64),
                0, "natural-pocket-seal", 0, miningCursor(face, 0, 1));
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("natural open disposal pocket ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getBlockPos().equals(face),
                    "natural-pocket service lost its exact work face");
            require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                    "floor-first transaction did not double-seal the natural mouth");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && freeMainSlots(bot) >= 4,
                    "natural-pocket capacity handoff did not spend two seals and free a slot");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 64,
                    "capacity handoff discarded its protected mining reserve");
            require(context, sinkCount(bot, sink, Items.DIRT) >= 62,
                    "natural-pocket ledger did not remain physically contained in the sink");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceUnsafeGeometryRerouteStrict", tickLimit = 500)
    public void unsafeOpenCaveSealsThenUsesOppositeDisposalPocket(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceGeometryRerouteGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        // Control the two-block spoil stack directly. Vanilla opening drops may reach the player
        // zero, one, or two at a time depending on entity motion, which obscures the selector this
        // regression is intended to lock.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLED_DEEPSLATE, 2));
        for (int index = 0; index < 28; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "geometry-reroute fixture did not require a capacity handoff");

        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos unsafeEntry = face.east();
        BlockPos unsafeSink = face.east(2);
        BlockPos safeEntry = face.west();
        BlockPos safeSink = face.west(2);
        var world = bot.getServerWorld();
        // Reproduce the live seed-3000 geometry: the preferred mouth is mineable, but opening it
        // reveals an existing cave instead of a supported two-cell sink.  The opposite side is a
        // normal bounded pocket and must not become active until the unsafe mouth is double-sealed.
        world.setBlockState(unsafeEntry,
                Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unsafeEntry.up(),
                Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unsafeSink,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unsafeSink.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(face.east(3),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(face.east(3).up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        prepareDisposalPocket(fixture, Direction.WEST);
        // Keep this regression about geometry rerouting and seal-source selection, not random
        // opening-drop motion. Glass still requires four physical breaks but produces no survival
        // drop, so the controlled spoil stack and disposable inventory independently prove the
        // promised four free slots.
        for (BlockPos cell : new BlockPos[]{safeEntry, safeEntry.up(), safeSink, safeSink.up()}) {
            world.setBlockState(cell, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.capacityHandoff(64),
                0, "unsafe-geometry-reroute", 0, miningCursor(face, 0, 1));
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("unsafe geometry reroute ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getBlockPos().equals(face),
                    "geometry reroute lost its exact work face");
            require(context, isSolid(bot, unsafeEntry) && isSolid(bot, unsafeEntry.up()),
                    "opposite pocket started before the unsafe mouth was double-sealed");
            require(context, isSolid(bot, safeEntry) && isSolid(bot, safeEntry.up()),
                    "alternate disposal pocket did not finish with a double seal");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && freeMainSlots(bot) >= 4,
                    "alternate pocket did not complete the promised capacity handoff");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 64,
                    "geometry reroute discarded its protected mining reserve");
            require(context, InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE) == 0
                            && world.getBlockState(safeEntry).isOf(Blocks.COBBLED_DEEPSLATE)
                            && world.getBlockState(safeEntry.up()).isOf(Blocks.COBBLED_DEEPSLATE),
                    "alternate seal did not consume the slot-releasing surplus stone stack");
            require(context, sinkCount(bot, safeSink, Items.DIRT) >= 62,
                    "alternate sink did not retain the post-reroute disposal ledger");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceDoubleUnsafeGeometryStrict", tickLimit = 300)
    public void twoUnsafeOpenCavesSealOnceEachAndFailWithoutPingPong(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceDoubleGeometryGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "double-geometry fixture did not require a capacity handoff");

        BlockPos face = bot.getBlockPos().toImmutable();
        var world = bot.getServerWorld();
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST}) {
            BlockPos entry = face.offset(direction);
            BlockPos sink = face.offset(direction, 2);
            world.setBlockState(entry,
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(entry.up(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(sink,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(sink.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(face.offset(direction, 3),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(face.offset(direction, 3).up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.capacityHandoff(64),
                0, "double-unsafe-geometry", 0, miningCursor(face, 0, 1));
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "mining_service_disposal_geometry_unsafe"
                            .equals(task.failureReason()),
                    "two unsafe pockets did not terminate with the exact geometry failure: "
                            + task.state() + ":" + task.failureReason());
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up())
                            && isSolid(bot, face.west()) && isSolid(bot, face.west().up()),
                    "bounded geometry failure left a mouth open or retried it again");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 60,
                    "double geometry seal did not consume exactly four physical blocks");
            Map<String, String> terminal = task.checkpoint();
            require(context, "SEAL_DISPOSAL_POCKET".equals(terminal.get("phase"))
                            && "true".equals(terminal.get("pocket_drop_committed"))
                            && "mining_service_disposal_geometry_unsafe"
                            .equals(terminal.get("pocket_failure"))
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "double geometry terminal debt was not restartable: " + terminal);
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePartialLowerSealRestartStrict", tickLimit = 500)
    public void lowerOnlySealRestartClosesHeadThenFailsLedgerVisibility(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceLowerOnlyRestartGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos entry = face.east();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "lower-only-restart", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restarted = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restarted.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                    && !live.getOrDefault("pocket_ledger", "").isBlank()) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent()
                                && InventoryAction.countItem(bot, Items.DIRT) == 2,
                        "lower-only restart fixture did not reach a valid pre-seal debt: " + live);
                task.abort(bot);
                bot.getServerWorld().setBlockState(
                        entry, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
                require(context, InventoryAction.removeItems(bot, Items.DIRT, 1)
                                && isSolid(bot, entry)
                                && bot.getServerWorld().getBlockState(entry.up()).isAir(),
                        "fixture could not reproduce the lower-only physical crash boundary");
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "lower-only-restart", 0, cursor);
                active[0].start(bot);
                require(context, active[0].state() == TaskState.RUNNING
                                && "SEAL_DISPOSAL_POCKET".equals(
                                active[0].checkpoint().get("phase")),
                        "lower-only debt did not restart in SEAL");
                restarted.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(
                        "lower-only restart incorrectly ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restarted.get()
                            && "mining_service_disposal_ledger_lost_before_seal"
                            .equals(task.failureReason()),
                    "lower-only restart lost its fail-closed ledger outcome: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.up())
                            && InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "lower-only restart failed before completing the physical head seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent()
                            && !task.checkpoint().getOrDefault(
                            "pocket_ledger", "").isBlank(),
                    "lower-only terminal debt lost its restartable ledger checkpoint");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void restoredHardBudgetCannotBeResetByRestart(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceBudgetGT", false);
        Map<String, String> checkpoint = validCheckpoint(fixture.bot(), "4800", "4800");
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint);
        task.start(fixture.bot());
        task.tick(fixture.bot());

        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("mining_service_timeout:"),
                "restart reset the service hard budget: " + task.state()
                        + ":" + task.failureReason());
        Map<String, String> terminal = task.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && "8".equals(terminal.get("schema"))
                        && "ORE_BATCH".equals(terminal.get("service_profile"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "legacy service did not migrate into a valid schema-8 checkpoint: " + terminal);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void malformedCheckpointFailsClosed(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceInvalidGT", false);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                validCheckpoint(fixture.bot(), "10", "5"));
        checkpoint.put("unknown", "must_fail_closed");
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint".equals(task.failureReason()),
                "malformed checkpoint silently became a fresh service: "
                        + task.state() + ":" + task.failureReason());
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketCheckpointStrict", tickLimit = 40)
    public void pocketCheckpointCountsAndPhaseAuthorityAreStrictlyBounded(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServicePocketCountGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "pocket-count-bound", 0, cursor);
        original.start(bot);
        require(context, MiningServiceTask.checkedPocketLedgerCount(
                        MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT - 1, 1)
                        == MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT
                        && MiningServiceTask.checkedPocketLedgerCount(
                        MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT, 1) == -1
                        && MiningServiceTask.checkedPocketLedgerCount(Integer.MAX_VALUE, 1) == -1,
                "runtime pocket ledger count did not enforce its exact MAX boundary");
        boolean invalidEncodeRejected = false;
        try {
            MiningServiceTask.encodeItemLedger(Map.of(Items.DIRT, 0));
        } catch (IllegalStateException expected) {
            invalidEncodeRejected = expected.getMessage().startsWith(
                    "invalid_pocket_ledger:");
        }
        require(context, invalidEncodeRejected,
                "checkpoint encoder silently filtered an invalid internal ledger entry");
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        require(context, MiningServiceTask.inspectCheckpoint(open).isPresent(),
                "live OPEN pocket checkpoint did not decode: " + open);

        Map<String, String> baselineBoundary = new LinkedHashMap<>(open);
        baselineBoundary.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        baselineBoundary.put("pocket_clear_index", "4");
        baselineBoundary.put("pocket_baseline", "minecraft:dirt="
                + MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT);
        require(context, MiningServiceTask.inspectCheckpoint(baselineBoundary).isPresent(),
                "exact pocket baseline boundary was rejected: " + baselineBoundary);

        Map<String, String> baselinePastBoundary = new LinkedHashMap<>(baselineBoundary);
        baselinePastBoundary.put("pocket_baseline", "minecraft:dirt="
                + (MiningServiceTask.POCKET_CHECKPOINT_MAX_ITEM_COUNT + 1));
        require(context, MiningServiceTask.inspectCheckpoint(baselinePastBoundary).isEmpty(),
                "past-boundary pocket baseline retained restore authority");

        String tracked = "00000000-0000-0000-0000-000000000064";
        Map<String, String> prebaselineCapture = new LinkedHashMap<>(open);
        prebaselineCapture.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        prebaselineCapture.put("pocket_clear_index", "4");
        prebaselineCapture.put("pocket_entities", tracked);
        prebaselineCapture.put("pocket_drop_committed", "true");
        require(context, MiningServiceTask.inspectCheckpoint(prebaselineCapture).isPresent(),
                "CAPTURE prebaseline identity lost its distinct checkpoint authority: "
                        + prebaselineCapture);

        Map<String, String> oneStackLedger = new LinkedHashMap<>(open);
        oneStackLedger.put("phase", "SETTLE_DISPOSABLE");
        oneStackLedger.put("pocket_clear_index", "4");
        oneStackLedger.put("pocket_entities", tracked);
        oneStackLedger.put("pocket_ledger", "minecraft:dirt=64");
        require(context, MiningServiceTask.inspectCheckpoint(oneStackLedger).isPresent(),
                "one tracked full stack stopped decoding: " + oneStackLedger);

        Map<String, String> staleVerifiedSettle = new LinkedHashMap<>(oneStackLedger);
        staleVerifiedSettle.put("pocket_drop_committed", "true");
        staleVerifiedSettle.put("pocket_ledger_verified", "true");
        require(context, MiningServiceTask.inspectCheckpoint(staleVerifiedSettle).isEmpty(),
                "SETTLE checkpoint retained stale SEAL verification authority");

        Map<String, String> ledgerPastIdentity = new LinkedHashMap<>(oneStackLedger);
        ledgerPastIdentity.put("pocket_ledger", "minecraft:dirt=65");
        require(context, MiningServiceTask.inspectCheckpoint(ledgerPastIdentity).isEmpty(),
                "one tracked UUID authorized more than one survival stack");

        String trackedSecond = "00000000-0000-0000-0000-000000000065";
        Map<String, String> twoStackLedger = new LinkedHashMap<>(ledgerPastIdentity);
        twoStackLedger.put("pocket_entities", tracked + "," + trackedSecond);
        require(context, MiningServiceTask.inspectCheckpoint(twoStackLedger).isPresent(),
                "two tracked UUIDs did not authorize a legal 65-item ledger: "
                        + twoStackLedger);

        Map<String, String> twoTypesOneIdentity = new LinkedHashMap<>(oneStackLedger);
        twoTypesOneIdentity.put(
                "pocket_ledger", "minecraft:dirt=1;minecraft:gravel=1");
        require(context, MiningServiceTask.inspectCheckpoint(twoTypesOneIdentity).isEmpty(),
                "one tracked UUID authorized two distinct item identities");

        Map<String, String> paddedIdentity = new LinkedHashMap<>(oneStackLedger);
        paddedIdentity.put("pocket_entities", tracked + "," + trackedSecond);
        paddedIdentity.put("pocket_ledger", "minecraft:dirt=1");
        require(context, MiningServiceTask.inspectCheckpoint(paddedIdentity).isEmpty(),
                "more UUIDs than recorded items retained checkpoint authority");

        Map<String, String> emptyLedgerIdentity = new LinkedHashMap<>(oneStackLedger);
        emptyLedgerIdentity.put("pocket_ledger", "");
        emptyLedgerIdentity.put("pocket_drop_committed", "true");
        require(context, MiningServiceTask.inspectCheckpoint(emptyLedgerIdentity).isEmpty(),
                "non-CAPTURE UUID retained authority without any ledger item");

        Map<String, String> integerMaxLedger = new LinkedHashMap<>(oneStackLedger);
        integerMaxLedger.put("pocket_ledger", "minecraft:dirt=" + Integer.MAX_VALUE);
        require(context, MiningServiceTask.inspectCheckpoint(integerMaxLedger).isEmpty(),
                "Integer.MAX_VALUE pocket ledger retained restore authority");

        Map<String, String> committedReturn = new LinkedHashMap<>(oneStackLedger);
        committedReturn.put("phase", "RETURN_TO_DISPOSAL_FACE");
        committedReturn.put("pocket_drop_committed", "true");
        require(context, MiningServiceTask.inspectCheckpoint(committedReturn).isPresent(),
                "committed return checkpoint stopped decoding: " + committedReturn);
        Map<String, String> baselineBackedReturn = new LinkedHashMap<>(committedReturn);
        baselineBackedReturn.put("pocket_baseline", "minecraft:dirt=2");
        require(context, MiningServiceTask.inspectCheckpoint(baselineBackedReturn).isEmpty(),
                "baseline=2 plus ledger=64 incorrectly fit into one survival lineage root: "
                        + baselineBackedReturn);
        baselineBackedReturn.put("pocket_entities", tracked + "," + trackedSecond);
        require(context, MiningServiceTask.inspectCheckpoint(baselineBackedReturn).isPresent(),
                "two lineage roots did not authorize physical baseline=2 plus ledger=64: "
                        + baselineBackedReturn);
        Map<String, String> uncommittedReturn = new LinkedHashMap<>(committedReturn);
        uncommittedReturn.put("pocket_drop_committed", "false");
        require(context, MiningServiceTask.inspectCheckpoint(uncommittedReturn).isPresent(),
                "ledger-backed return checkpoint lost its physical-debt authority");

        Map<String, String> debtFreeReturn = new LinkedHashMap<>(open);
        debtFreeReturn.put("phase", "RETURN_TO_DISPOSAL_FACE");
        require(context, MiningServiceTask.inspectCheckpoint(debtFreeReturn).isEmpty(),
                "return checkpoint without committed or ledger debt retained authority");

        Map<String, String> nonPocketPhase = new LinkedHashMap<>(open);
        nonPocketPhase.put("phase", "PREPARE");
        require(context, MiningServiceTask.inspectCheckpoint(nonPocketPhase).isEmpty(),
                "pocket payload was accepted outside a pocket phase");

        original.abort(bot);
        MiningServiceTask maliciousRestore = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), integerMaxLedger, policy,
                0, "pocket-count-bound", 0, cursor);
        maliciousRestore.start(bot);
        require(context, maliciousRestore.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint"
                        .equals(maliciousRestore.failureReason()),
                "malicious ledger did not fail with the typed checkpoint error: "
                        + maliciousRestore.state() + ":" + maliciousRestore.failureReason());
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketHardWindowStrict", tickLimit = 80)
    public void openRetryMarkerAtHardBudgetBecomesTerminalAndCannotReroute(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceOpenRetryBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "open-retry-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction selected = Direction.valueOf(open.get("pocket_direction"));
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        hard.put("pocket_failure", "retry_disposal_ore:"
                + selected.getOpposite().name() + ":fixture");
        require(context, MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "OPEN retry hard-window checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "open-retry-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:OPEN_DISPOSAL_POCKET"
                        .equals(restored.failureReason()),
                "OPEN retry marker survived hard terminal recovery: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, face.offset(selected))
                        && isSolid(bot, face.offset(selected).up()),
                "OPEN retry hard timeout failed before double seal");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && !terminal.getOrDefault("pocket_failure", "")
                        .startsWith("retry_disposal_ore:")
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "OPEN retry hard timeout reset budget or retained routing authority: "
                        + terminal);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketHardWindowStrict", tickLimit = 80)
    public void sealRetryMarkerAtHardBudgetBecomesTerminalAndCannotPingPong(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceSealRetryBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "seal-retry-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction selected = Direction.valueOf(open.get("pocket_direction"));
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("phase", "SEAL_DISPOSAL_POCKET");
        hard.put("pocket_drop_committed", "true");
        hard.put("pocket_failure", "retry_disposal_ore:"
                + selected.name() + ":fixture");
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        require(context, MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "SEAL retry hard-window checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "seal-retry-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:SEAL_DISPOSAL_POCKET"
                        .equals(restored.failureReason()),
                "SEAL retry marker ping-ponged past the hard window: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, face.offset(selected))
                        && isSolid(bot, face.offset(selected).up()),
                "SEAL retry hard timeout failed before double seal");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "SEAL retry terminal checkpoint reset budget or became invalid: " + terminal);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketRerouteAtomic", tickLimit = 100)
    public void sealedOldPocketAlternateStartFailureLeavesValidNonPocketCheckpoint(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceRerouteAtomicGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "reroute-atomic", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction rejected = Direction.valueOf(open.get("pocket_direction"));
        Direction alternate = rejected.getOpposite();
        BlockPos oldEntry = face.offset(rejected);
        BlockPos alternateEntry = face.offset(alternate);
        bot.getServerWorld().setBlockState(oldEntry,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(oldEntry.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(alternateEntry,
                Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace(
                "depot", bot.getServerWorld(), alternateEntry);
        Map<String, String> retry = new LinkedHashMap<>(open);
        retry.put("phase", "SEAL_DISPOSAL_POCKET");
        retry.put("pocket_drop_committed", "true");
        retry.put("pocket_failure", "retry_disposal_ore:"
                + rejected.name() + ":fixture");
        require(context, MiningServiceTask.inspectCheckpoint(retry).isPresent(),
                "reroute atomic fixture checkpoint was invalid: " + retry);
        int budgetBefore = Integer.parseInt(retry.get("budget_used"));
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), retry, policy,
                0, "reroute-atomic", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 20 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith(
                        "mining_service_disposal_no_alternate_after_ore:"),
                "alternate-start failure did not remain bounded: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, oldEntry) && isSolid(bot, oldEntry.up()),
                "alternate selection ran before old pocket was double-sealed");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "PREPARE".equals(terminal.get("phase"))
                        && terminal.keySet().stream()
                        .noneMatch(key -> key.startsWith("pocket_"))
                        && Integer.parseInt(terminal.get("budget_used")) >= budgetBefore
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "alternate-start failure published a half-pocket checkpoint: " + terminal);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceGeometryDebtHardWindow", tickLimit = 80)
    public void restoredOpenClearZeroWithFactuallyBrokenEntrySealsAtHardWindow(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceOpenMutationBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "open-mutation-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.offset(direction);
        bot.getServerWorld().setBlockState(entry,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        require(context, "0".equals(hard.get("pocket_clear_index"))
                        && MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "OPEN clear-zero factual-mutation checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "open-mutation-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:OPEN_DISPOSAL_POCKET"
                        .equals(restored.failureReason()),
                "OPEN clear-zero mutation bypassed terminal recovery: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                "OPEN factual first-break was left unsealed at hard timeout");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "OPEN geometry terminal checkpoint reset budget or became invalid: "
                        + terminal);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceGeometryDebtHardWindow", tickLimit = 80)
    public void restoredCaptureEmptyLedgerSealsAtHardWindow(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceCaptureMutationBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "capture-mutation-budget", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.offset(direction);
        BlockPos sink = face.offset(direction, 2);
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        Map<String, String> hard = new LinkedHashMap<>(open);
        hard.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        hard.put("pocket_clear_index", "4");
        hard.put("budget_used", "4800");
        hard.put("last_progress_budget", "4800");
        require(context, hard.getOrDefault("pocket_ledger", "").isBlank()
                        && hard.getOrDefault("pocket_entities", "").isBlank()
                        && MiningServiceTask.inspectCheckpoint(hard).isPresent(),
                "CAPTURE empty-ledger hard checkpoint was invalid: " + hard);
        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), hard, policy,
                0, "capture-mutation-budget", 0, cursor);
        restored.start(bot);
        for (int tick = 0; tick < 10 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }
        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_timeout:CAPTURE_DISPOSAL_BASELINE"
                        .equals(restored.failureReason()),
                "CAPTURE empty-ledger geometry debt bypassed terminal recovery: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                "CAPTURE geometry debt failed before double seal");
        Map<String, String> terminal = restored.checkpoint();
        require(context, "4800".equals(terminal.get("budget_used"))
                        && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                "CAPTURE hard terminal checkpoint reset budget or became invalid: "
                        + terminal);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void fullHungerAndRawMeatDoNotBypassSafeReserve(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceRawFoodGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BEEF, 64));
        bot.getHungerManager().setFoodLevel(20);

        MiningServiceTask task = new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE));
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED) {
                require(context, task.failureReason().startsWith(
                                "mining_service_food_reserve_depleted:have=0:required=2"),
                        "wrong fail-closed reason: " + task.failureReason());
                require(context, InventoryAction.countItem(bot, Items.BEEF) == 64,
                        "service consumed raw meat instead of rejecting it");
                cleanup(context, fixture);
            } else if (task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("unsafe food reserve ended as " + task.state());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 500)
    public void depotWithdrawsSafeFoodAndLeavesDangerousFoodUntouched(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceDepotFoodGT", true);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Inventory depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        depot.setStack(0, new ItemStack(Items.ROTTEN_FLESH, 8));
        depot.setStack(1, new ItemStack(Items.BREAD, 2));
        depot.markDirty();

        MiningServiceTask task = new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE));
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("safe depot service ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BREAD) == 2,
                    "service did not withdraw the two-unit safe reserve");
            require(context, depot.getStack(0).isOf(Items.ROTTEN_FLESH)
                            && depot.getStack(0).getCount() == 8,
                    "service withdrew dangerous food before safe food");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void localCraftsTunnelingToolsWithoutDepot(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceLocalToolsGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 28));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), true);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("local channel-tool service ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 4,
                    "service did not locally craft four tunneling picks");
            require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1,
                    "service consumed the target-grade iron pickaxe");
            require(context, InventoryAction.countItem(bot, Items.BREAD) == 2,
                    "service consumed the safe reserve instead of checking it");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 16,
                    "local craft consumed the emergency stone reserve");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 700)
    public void noDepotThreeFreeSlotsReclaimsDeadPicksAndJunkBeforeService(
            TestContext context) {
        require(context, MiningServiceTask.reconciledPocketBaselineCount(
                        2, 64, 64, 64) == 0
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 65, 64, 64) == 1
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 64, 64, 63) == 2
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 63, 64, 64) == 2
                        && MiningServiceTask.reconciledPocketBaselineCount(
                        2, 66, 64, 64) == 2,
                "baseline reconciliation policy no longer requires an intact tracked ledger");
        Fixture fixture = spawn(context, "MiningServiceNoDepotSlotsGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 30));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        giveExhaustedPick(bot, Items.WOODEN_PICKAXE);
        giveExhaustedPick(bot, Items.STONE_PICKAXE);
        giveExhaustedPick(bot, Items.IRON_PICKAXE);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 22; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }

        require(context, freeMainSlots(bot) == 3,
                "test precondition did not leave exactly three free slots: "
                        + freeMainSlots(bot));
        require(context, unusableCheapPickaxes(bot) == 3,
                "test precondition did not carry three unusable cheap pickaxes");

        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(true),
                0, "sealed-pocket-test", 0, cursor);
        task.start(bot);
        int[] serviceTicks = {0};
        int[] serviceCompletedAt = {-1};
        OreDigTask[] nextBatch = {null};
        BlockPos nextOre = bot.getBlockPos().north(2).toImmutable();
        AtomicReference<ItemEntity> controlledBaseline = new AtomicReference<>();
        AtomicBoolean baselineRemoved = new AtomicBoolean();
        AtomicBoolean baselineRebased = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            serviceTicks[0]++;
            Map<String, String> before = task.checkpoint();
            if (task.state() == TaskState.RUNNING
                    && controlledBaseline.get() == null
                    && "CAPTURE_DISPOSAL_BASELINE".equals(before.get("phase"))) {
                ItemEntity baseline = new ItemEntity(
                        bot.getServerWorld(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                        sink.getZ() + 0.5D, new ItemStack(Items.DIRT, 2));
                baseline.setVelocity(Vec3d.ZERO);
                baseline.setPickupDelayInfinite();
                require(context, bot.getServerWorld().spawnEntity(baseline),
                        "three-slot fixture failed to spawn its controlled sink baseline");
                controlledBaseline.set(baseline);
            }
            if (task.state() == TaskState.RUNNING
                    && !baselineRemoved.get()
                    && controlledBaseline.get() != null
                    && "DROP_DISPOSABLE".equals(before.get("phase"))) {
                require(context, "minecraft:dirt=2".equals(before.get("pocket_baseline")),
                        "controlled baseline was not durably frozen: " + before);
                require(context, MiningServiceTask.inspectCheckpoint(before).isPresent(),
                        "baseline identity reset checkpoint failed strict decoding: " + before);
                controlledBaseline.get().discard();
                baselineRemoved.set(true);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (baselineRemoved.get()
                    && live.getOrDefault("pocket_ledger", "").contains("minecraft:dirt=")
                    && !live.getOrDefault("pocket_baseline", "")
                    .contains("minecraft:dirt=")) {
                baselineRebased.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("three-slot no-depot service ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            if (serviceCompletedAt[0] < 0) {
                serviceCompletedAt[0] = serviceTicks[0];
                require(context, bot.getBlockPos().equals(face),
                        "service did not complete at the exact cursor face anchor");
                require(context, unusableCheapPickaxes(bot) == 1,
                        "service dropped the exhausted iron pickaxe instead of preserving it");
                require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 2,
                        "service did not preserve both healthy and exhausted iron pickaxes");
                require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 4,
                        "service did not craft four replacement tunneling pickaxes");
                int channelDurability = bot.getInventory().main.stream()
                        .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                        .mapToInt(MiningServiceTask::usableDurability)
                        .sum();
                require(context, channelDurability >= 520,
                        "replacement tunneling durability is below four fresh stone picks: "
                                + channelDurability);
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 17,
                        "cleanup did not retain the post-service target-support allowance");
                require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                                && InventoryAction.countItem(bot, Items.GRAVEL) == 0
                                && InventoryAction.countItem(bot, Items.SAND) == 0,
                        "service did not drop enough low-value stacks to fund its craft outputs");
                require(context, freeMainSlots(bot) >= 4,
                        "service completed without the four-slot postcondition: free="
                                + freeMainSlots(bot));
                require(context, baselineRemoved.get() && baselineRebased.get(),
                        "three-slot service skipped the controlled baseline rebase race");
                bot.getServerWorld().setBlockState(nextOre,
                        Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
                nextBatch[0] = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
                nextBatch[0].start(bot);
            }
            if (nextBatch[0].state() == TaskState.RUNNING) {
                nextBatch[0].tick(bot);
            }
            if (nextBatch[0].state() == TaskState.FAILED
                    || nextBatch[0].state() == TaskState.CANCELLED) {
                context.throwGameTestException("post-service ore batch ended as "
                        + nextBatch[0].state() + ":" + nextBatch[0].failureReason());
            }
            if (nextBatch[0].state() != TaskState.COMPLETED
                    || serviceTicks[0] - serviceCompletedAt[0] <= 100) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                    "next OreDig batch did not physically collect its target");
            require(context, unusableCheapPickaxes(bot) == 1,
                    "service lost the preserved exhausted iron pickaxe after mining resumed");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && InventoryAction.countItem(bot, Items.GRAVEL) == 0
                            && InventoryAction.countItem(bot, Items.SAND) == 0,
                    "junk returned after the next mining batch started");
            require(context, freeMainSlots(bot) > 0,
                    "post-service pickup consumed every reclaimed inventory slot");
            java.util.List<ItemEntity> sealed = bot.getServerWorld().getEntitiesByClass(
                    ItemEntity.class, sinkBox(sink), ItemEntity::isAlive);
            require(context, !sealed.isEmpty(),
                    "disposal ledger had no surviving vanilla ItemEntity in the sealed sink");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "disposal pocket mouth was not sealed at both player cells");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 500)
    public void committedDisposalRestoreWaitsPastPickupDelayAndSealsWithoutRedrop(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServicePocketRestoreGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "restore fixture did not begin with exactly three free slots");

        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "pocket-restore", 0, cursor);
        original.start(bot);

        Map<String, String>[] committed = new Map[]{null};
        MiningServiceTask[] restored = {null};
        int[] committedAt = {-1};
        int[] elapsed = {0};
        boolean[] suppliesRestarted = {false};
        context.runAtEveryTick(() -> {
            elapsed[0]++;
            if (committed[0] == null && original.state() == TaskState.RUNNING) {
                original.tick(bot);
                Map<String, String> live = original.checkpoint();
                if ("SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                        && "true".equals(live.get("pocket_drop_committed"))) {
                    committed[0] = live;
                    committedAt[0] = elapsed[0];
                    original.abort(bot);
                    require(context, InventoryAction.countItem(bot, Items.DIRT) == 2,
                            "committed checkpoint did not retain exactly two physical seal blocks");
                    require(context, bot.getBlockPos().equals(face),
                            "drop-committed checkpoint left the bot at the pocket mouth");
                }
            }
            if (committed[0] == null && original.state() == TaskState.FAILED) {
                context.throwGameTestException("original disposal failed: "
                        + original.failureReason());
            }
            if (committed[0] == null || restored[0] != null
                    || elapsed[0] - committedAt[0] <= 60) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 2,
                    "vanilla drop returned after its default pickup delay during interruption");
            require(context, sinkCount(bot, face.east(2), Items.DIRT) >= 62,
                    "committed dirt ledger was not physically present in the sink before restore");
            restored[0] = new MiningServiceTask(
                    Set.of(Blocks.DIAMOND_ORE), committed[0], policy,
                    0, "pocket-restore", 0, cursor);
            restored[0].start(bot);
        });
        context.runAtEveryTick(() -> {
            if (restored[0] == null) {
                return;
            }
            if (restored[0].state() == TaskState.RUNNING) {
                restored[0].tick(bot);
            }
            Map<String, String> live = restored[0].checkpoint();
            if (!suppliesRestarted[0] && "SUPPLIES".equals(live.get("phase"))) {
                require(context, live.keySet().stream()
                                .noneMatch(key -> key.startsWith("pocket_")),
                        "settled SUPPLIES checkpoint retained pocket transaction payload: "
                                + live);
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "settled SUPPLIES checkpoint could not decode itself: " + live);
                String budget = live.get("budget_used");
                restored[0].abort(bot);
                restored[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "pocket-restore", 0, cursor);
                restored[0].start(bot);
                require(context, budget.equals(restored[0].checkpoint().get("budget_used")),
                        "SUPPLIES restore refreshed the service hard budget");
                suppliesRestarted[0] = true;
                return;
            }
            if (restored[0].state() == TaskState.FAILED
                    || restored[0].state() == TaskState.CANCELLED) {
                context.throwGameTestException("restored disposal ended as "
                        + restored[0].state() + ":" + restored[0].failureReason());
            }
            if (restored[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, suppliesRestarted[0],
                    "fixture never restored the post-seal SUPPLIES checkpoint");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "restore repeated disposal or skipped spending the two seal blocks");
            require(context, InventoryAction.countItem(bot, Items.GLASS) == 30 * 64,
                    "restore discarded a protected non-junk inventory stack");
            require(context, bot.getBlockPos().equals(face),
                    "restored disposal did not finish at the exact cursor face");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "restored disposal skipped one of the two physical mouth seals");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 700)
    public void committedSettlePauseMoveResumeReturnsAndSealsBothMouthCells(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceDebtReturnGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "debt-return", 0, cursor);
        task.start(bot);
        MiningServiceTask[] active = {task};
        boolean[] moved = {false};
        boolean[] checkpointedReturn = {false};

        context.runAtEveryTick(() -> {
            if (active[0].state() == TaskState.RUNNING) {
                active[0].tick(bot);
            }
            Map<String, String> live = active[0].checkpoint();
            if (!moved[0]
                    && "SETTLE_DISPOSABLE".equals(live.get("phase"))
                    && !live.getOrDefault("pocket_ledger", "").isBlank()) {
                active[0].pause(bot);
                BlockPos away = face.west();
                bot.teleport(bot.getServerWorld(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                active[0].resume(bot);
                moved[0] = true;
                return;
            }
            if (moved[0] && !checkpointedReturn[0]
                    && "RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "return-to-pocket debt checkpoint was not restartable: " + live);
                require(context, Integer.parseInt(live.get("budget_used")) > 0,
                        "return-to-pocket checkpoint reset its hard budget");
                active[0].abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live,
                        MiningServiceTask.ServicePolicy.defaultOre(false),
                        0, "debt-return", 0, cursor);
                active[0].start(bot);
                require(context, live.get("budget_used").equals(
                                active[0].checkpoint().get("budget_used")),
                        "RETURN restore refreshed the committed hard budget");
                checkpointedReturn[0] = true;
            }
            if (active[0].state() == TaskState.FAILED
                    || active[0].state() == TaskState.CANCELLED) {
                context.throwGameTestException("moved disposal debt ended as "
                        + active[0].state() + ":" + active[0].failureReason());
            }
            if (active[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, moved[0] && checkpointedReturn[0],
                    "fixture never exercised the durable return phase");
            require(context, bot.getBlockPos().equals(face),
                    "debt recovery did not return to the exact work face");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "debt recovery completed without both observable mouth seals");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceUnsealedReturnStrict", tickLimit = 500)
    public void unreachableUnsealedReturnFailsBoundedlyAndKeepsRestartableDebt(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceUnsealedReturnGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos entry = face.east();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "unsealed-return", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean trapped = new AtomicBoolean();
        AtomicReference<String> ledger = new AtomicReference<>();
        AtomicReference<String> identities = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!trapped.get()
                    && "SETTLE_DISPOSABLE".equals(live.get("phase"))
                    && !live.getOrDefault("pocket_ledger", "").isBlank()) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "unsealed-return fixture produced an invalid SETTLE checkpoint: " + live);
                ledger.set(live.get("pocket_ledger"));
                identities.set(live.get("pocket_entities"));
                require(context, !identities.get().isBlank(),
                        "unsealed-return fixture committed a ledger without UUID authority");
                task.abort(bot);
                BlockPos away = face.west(2);
                bot.teleport(bot.getServerWorld(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                buildBedrockCage(bot, away);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "unsealed-return", 0, cursor);
                active[0].start(bot);
                trapped.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("unreachable unsealed debt ended as "
                        + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, trapped.get()
                            && task.failureReason().startsWith(
                            "mining_service_disposal_unsealed_return_failed:"),
                    "unreachable unsealed return lost its typed bounded failure: "
                            + task.failureReason());
            Map<String, String> terminal = task.checkpoint();
            require(context, "RETURN_TO_DISPOSAL_FACE".equals(terminal.get("phase"))
                            && task.failureReason().equals(terminal.get("pocket_failure"))
                            && ledger.get().equals(terminal.get("pocket_ledger"))
                            && identities.get().equals(terminal.get("pocket_entities"))
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "unreachable return cleared or invalidated unresolved pocket debt: "
                            + terminal);
            require(context, !isSolid(bot, entry) && !isSolid(bot, entry.up()),
                    "unreachable return claimed a physical seal it could not reach");

            MiningServiceTask restored = new MiningServiceTask(
                    Set.of(Blocks.DIAMOND_ORE), terminal, policy,
                    0, "unsealed-return", 0, cursor);
            restored.start(bot);
            Map<String, String> restarted = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && "RETURN_TO_DISPOSAL_FACE".equals(restarted.get("phase"))
                            && ledger.get().equals(restarted.get("pocket_ledger"))
                            && identities.get().equals(restarted.get("pocket_entities"))
                            && MiningServiceTask.inspectCheckpoint(restarted).isPresent(),
                    "terminal unsealed debt could not be restored without false settlement: "
                            + restarted);
            restored.abort(bot);
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePrebaselineReturnStrict", tickLimit = 1200)
    public void prebaselineCapturePauseMoveRestartReturnsInCaptureAndCompletes(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServicePrebaselineReturnGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 0,
                "prebaseline-return fixture was not inventory-full");
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "prebaseline-return", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restartedAway = new AtomicBoolean();
        AtomicBoolean returnedInCapture = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restartedAway.get()
                    && "CAPTURE_DISPOSAL_BASELINE".equals(live.get("phase"))
                    && "true".equals(live.get("pocket_drop_committed"))
                    && live.getOrDefault("pocket_ledger", "").isBlank()
                    && !live.getOrDefault("pocket_entities", "").isBlank()) {
                task.pause(bot);
                BlockPos away = face.west();
                bot.teleport(bot.getServerWorld(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                task.resume(bot);
                Map<String, String> moved = task.checkpoint();
                require(context, "CAPTURE_DISPOSAL_BASELINE".equals(moved.get("phase"))
                                && MiningServiceTask.inspectCheckpoint(moved).isPresent(),
                        "prebaseline move lost CAPTURE checkpoint authority: " + moved);
                String budget = moved.get("budget_used");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), moved, policy,
                        0, "prebaseline-return", 0, cursor);
                active[0].start(bot);
                require(context, budget.equals(active[0].checkpoint().get("budget_used")),
                        "prebaseline CAPTURE restart reset the hard budget");
                restartedAway.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restartedAway.get() && !returnedInCapture.get()
                    && bot.getBlockPos().equals(face)) {
                require(context, "CAPTURE_DISPOSAL_BASELINE".equals(live.get("phase"))
                                && MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "physical return skipped prebaseline containment/baseline authority: "
                                + live);
                returnedInCapture.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("prebaseline CAPTURE return ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restartedAway.get() && returnedInCapture.get(),
                    "fixture skipped durable CAPTURE return/restart");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "prebaseline return completed without double seal");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePrebaselineReturnStrict", tickLimit = 700)
    public void terminalPrebaselineReturnCheckpointRestoresSealsAndFailsOriginalReason(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServicePrebaselineTerminalGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "prebaseline-terminal", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restoredTerminal = new AtomicBoolean();
        AtomicBoolean sawTerminalSeal = new AtomicBoolean();
        String terminalReason =
                "mining_service_disposal_prebaseline_return_failed:fixture";

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restoredTerminal.get()
                    && "CAPTURE_DISPOSAL_BASELINE".equals(live.get("phase"))
                    && "true".equals(live.get("pocket_drop_committed"))
                    && live.getOrDefault("pocket_ledger", "").isBlank()
                    && !live.getOrDefault("pocket_entities", "").isBlank()) {
                Map<String, String> terminal = new LinkedHashMap<>(live);
                terminal.put("phase", "RETURN_TO_DISPOSAL_FACE");
                terminal.put("pocket_failure", terminalReason);
                require(context, MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                        "typed terminal prebaseline RETURN checkpoint was rejected: " + terminal);
                task.abort(bot);
                BlockPos away = face.west();
                bot.teleport(bot.getServerWorld(), away.getX() + 0.5D,
                        away.getY(), away.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), terminal, policy,
                        0, "prebaseline-terminal", 0, cursor);
                active[0].start(bot);
                restoredTerminal.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restoredTerminal.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "terminal prebaseline SEAL checkpoint rejected itself: " + live);
                sawTerminalSeal.set(true);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("terminal prebaseline debt ended as "
                        + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restoredTerminal.get() && sawTerminalSeal.get()
                            && terminalReason.equals(task.failureReason()),
                    "terminal prebaseline lost its original typed outcome: "
                            + task.failureReason());
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "terminal prebaseline failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "terminal prebaseline failure checkpoint lost restore authority");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceGeometryDebtMoveStrict", tickLimit = 700)
    public void openGeometryDebtMoveRestartsReturnAndFailsOnlyAfterDoubleSeal(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceOpenMoveDebtGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "open-move-debt", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.offset(direction);
        bot.getServerWorld().setBlockState(entry,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos away = face.west();
        bot.teleport(bot.getServerWorld(), away.getX() + 0.5D,
                away.getY(), away.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), open, policy,
                0, "open-move-debt", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restartedReturn = new AtomicBoolean();
        AtomicBoolean sawSeal = new AtomicBoolean();
        String expected = "mining_service_disposal_geometry_anchor_changed:phase="
                + "OPEN_DISPOSAL_POCKET:at=" + away.toShortString();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restartedReturn.get()
                    && "RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))) {
                require(context, expected.equals(live.get("pocket_failure"))
                                && MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "OPEN move did not publish restartable unresolved geometry debt: "
                                + live);
                String budget = live.get("budget_used");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "open-move-debt", 0, cursor);
                active[0].start(bot);
                require(context, budget.equals(active[0].checkpoint().get("budget_used")),
                        "OPEN geometry RETURN restart reset budget");
                restartedReturn.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restartedReturn.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "OPEN geometry SEAL checkpoint rejected itself: " + live);
                sawSeal.set(true);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("OPEN geometry debt ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restartedReturn.get() && sawSeal.get()
                            && expected.equals(task.failureReason()),
                    "OPEN geometry debt lost exact terminal reason: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                    "OPEN move debt failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "OPEN move terminal checkpoint lost restore authority");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceGeometryDebtMoveStrict", tickLimit = 700)
    public void captureEmptyLedgerMoveRestartsReturnAndFailsOnlyAfterDoubleSeal(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceCaptureMoveDebtGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "capture-move-debt", 0, cursor);
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_DISPOSAL_POCKET", 10);
        original.abort(bot);
        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        BlockPos entry = face.offset(direction);
        BlockPos sink = face.offset(direction, 2);
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        Map<String, String> capture = new LinkedHashMap<>(open);
        capture.put("phase", "CAPTURE_DISPOSAL_BASELINE");
        capture.put("pocket_clear_index", "4");
        require(context, MiningServiceTask.inspectCheckpoint(capture).isPresent(),
                "CAPTURE empty-ledger move fixture was invalid: " + capture);
        BlockPos away = face.west();
        bot.teleport(bot.getServerWorld(), away.getX() + 0.5D,
                away.getY(), away.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), capture, policy,
                0, "capture-move-debt", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restartedReturn = new AtomicBoolean();
        AtomicBoolean sawSeal = new AtomicBoolean();
        String expected = "mining_service_disposal_geometry_anchor_changed:phase="
                + "CAPTURE_DISPOSAL_BASELINE:at=" + away.toShortString();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restartedReturn.get()
                    && "RETURN_TO_DISPOSAL_FACE".equals(live.get("phase"))) {
                require(context, expected.equals(live.get("pocket_failure"))
                                && MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "CAPTURE move did not publish restartable geometry debt: " + live);
                String budget = live.get("budget_used");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "capture-move-debt", 0, cursor);
                active[0].start(bot);
                require(context, budget.equals(active[0].checkpoint().get("budget_used")),
                        "CAPTURE geometry RETURN restart reset budget");
                restartedReturn.set(true);
                return;
            }
            task = active[0];
            live = task.checkpoint();
            if (restartedReturn.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "CAPTURE geometry SEAL checkpoint rejected itself: " + live);
                sawSeal.set(true);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("CAPTURE geometry debt ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restartedReturn.get() && sawSeal.get()
                            && expected.equals(task.failureReason()),
                    "CAPTURE geometry debt lost exact terminal reason: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                    "CAPTURE move debt failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "CAPTURE move terminal checkpoint lost restore authority");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceRawSinkContainmentStrict", tickLimit = 180)
    public void straddlingTrackedItemMustEnterRawSinkBeforePresealStability(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceRawSinkGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERRACK, 2));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 3,
                "raw-sink fixture did not trigger the four-slot disposal boundary");
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask bootstrap = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "raw-sink-containment", 0, cursor);
        bootstrap.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                bootstrap, bot, "OPEN_DISPOSAL_POCKET", 10);
        bootstrap.abort(bot);
        require(context, InventoryAction.removeItems(bot, Items.GLASS, 30 * 64),
                "raw-sink fixture could not release its bootstrap-only filler slots");

        Direction direction = Direction.valueOf(open.get("pocket_direction"));
        require(context, direction == Direction.EAST,
                "raw-sink fixture selected its unprepared side: " + direction);
        BlockPos entry = face.offset(direction);
        BlockPos sink = face.offset(direction, 2);
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        ItemEntity tracked = new ItemEntity(
                bot.getServerWorld(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                sink.getZ() + 0.5D, new ItemStack(Items.DIRT, 64));
        tracked.setVelocity(Vec3d.ZERO);
        tracked.setNoGravity(true);
        tracked.setPickupDelayInfinite();
        double halfWidth = (tracked.getBoundingBox().maxX
                - tracked.getBoundingBox().minX) / 2.0D;
        double centerOffset = 0.5D - halfWidth + 0.005D;
        Vec3d sinkCenter = new Vec3d(
                sink.getX() + 0.5D, sink.getY() + 0.25D, sink.getZ() + 0.5D);
        Vec3d straddling = sinkCenter.subtract(
                direction.getOffsetX() * centerOffset,
                0.0D,
                direction.getOffsetZ() * centerOffset);
        tracked.refreshPositionAndAngles(
                straddling.x, straddling.y, straddling.z, 0.0F, 0.0F);
        Box rawSink = sinkBox(sink);
        require(context, !fullyContains(rawSink, tracked.getBoundingBox())
                        && fullyContains(rawSink.expand(0.01D), tracked.getBoundingBox()),
                "controlled item did not isolate raw containment from query tolerance: "
                        + tracked.getBoundingBox());
        require(context, bot.getServerWorld().spawnEntity(tracked),
                "failed to spawn controlled raw-sink ledger entity");

        Map<String, String> settle = new LinkedHashMap<>(open);
        settle.put("phase", "SETTLE_DISPOSABLE");
        settle.put("pocket_clear_index", "4");
        settle.put("pocket_entities", tracked.getUuidAsString());
        settle.put("pocket_lineage", tracked.getUuidAsString()
                + "@minecraft:dirt@64@L");
        settle.put("pocket_baseline", "");
        settle.put("pocket_ledger", "minecraft:dirt=64");
        settle.put("pocket_drop_committed", "true");
        settle.put("pocket_ledger_verified", "false");
        require(context, MiningServiceTask.inspectCheckpoint(settle).isPresent(),
                "raw-sink SETTLE fixture was not restartable: " + settle);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), settle, policy,
                0, "raw-sink-containment", 0, cursor);
        task.start(bot);
        require(context, task.state() == TaskState.RUNNING
                        && "SETTLE_DISPOSABLE".equals(task.checkpoint().get("phase")),
                "raw-sink checkpoint did not restart in SETTLE: "
                        + task.state() + ":" + task.failureReason());

        int[] straddlingTicks = {0};
        boolean[] movedInside = {false};
        context.runAtEveryTick(() -> {
            if (!movedInside[0]) {
                tracked.refreshPositionAndAngles(
                        straddling.x, straddling.y, straddling.z, 0.0F, 0.0F);
                tracked.setVelocity(Vec3d.ZERO);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("raw-sink containment ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (!movedInside[0]) {
                straddlingTicks[0]++;
                require(context, "SETTLE_DISPOSABLE".equals(
                                task.checkpoint().get("phase"))
                                && !isSolid(bot, entry) && !isSolid(bot, entry.up()),
                        "query tolerance granted physical custody at straddling tick "
                                + straddlingTicks[0] + ":" + task.checkpoint());
                if (straddlingTicks[0] >= 25) {
                    tracked.refreshPositionAndAngles(
                            sinkCenter.x, sinkCenter.y, sinkCenter.z, 0.0F, 0.0F);
                    tracked.setVelocity(Vec3d.ZERO);
                    movedInside[0] = true;
                }
                return;
            }
            if (!isSolid(bot, entry) || !isSolid(bot, entry.up())) {
                return;
            }
            require(context, tracked.isAlive()
                            && fullyContains(rawSink, tracked.getBoundingBox()),
                    "service sealed without factual raw-sink custody");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceTrackedEscapeStrict", tickLimit = 500)
    public void settlePhaseInFlightTrackedEntityCannotBeImpersonatedAndTimesOut(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceSettleEscapeGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "settle-tracked-escape", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<ItemEntity> escapedRef = new AtomicReference<>();
        AtomicReference<ItemEntity> impostorRef = new AtomicReference<>();
        AtomicReference<ItemEntity> nearerSpoilRef = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!injected.get()) {
                if (task.state() != TaskState.RUNNING) {
                    context.throwGameTestException(
                            "SETTLE tracked-escape fixture ended before injection: "
                                    + task.state() + ":" + task.failureReason());
                }
                if (!"SETTLE_DISPOSABLE".equals(live.get("phase"))
                        || live.getOrDefault("pocket_ledger", "").isBlank()) {
                    return;
                }
                String[] identities = live.getOrDefault("pocket_entities", "")
                        .split(",", -1);
                // A baseline-zero transaction legitimately has one lineage root: the ordinary
                // player drop recorded by the ledger.  Requiring a separate baseline UUID makes
                // this identity test depend on incidental opening-spoil timing instead of the
                // persisted lineage contract that the restart actually consumes.
                require(context, identities.length >= 1
                                && java.util.Arrays.stream(identities)
                                .noneMatch(String::isBlank)
                                && !live.getOrDefault("pocket_lineage", "").isBlank(),
                        "SETTLE fixture did not expose committed ledger lineage: " + live);
                ItemEntity escaped = java.util.Arrays.stream(identities)
                        .map(java.util.UUID::fromString)
                        .map(id -> bot.getServerWorld().getEntity(id))
                        .filter(ItemEntity.class::isInstance)
                        .map(ItemEntity.class::cast)
                        .filter(ItemEntity::isAlive)
                        .max(java.util.Comparator.comparingInt(
                                entity -> entity.getStack().getCount()))
                        .orElse(null);
                require(context, escaped != null && escaped.getStack().isOf(Items.DIRT),
                        "SETTLE lineage had no live dirt survivor before escape injection");
                ItemEntity impostor = new ItemEntity(
                        bot.getServerWorld(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                        sink.getZ() + 0.5D, escaped.getStack().copy());
                impostor.setVelocity(Vec3d.ZERO);
                impostor.setPickupDelayInfinite();
                require(context, bot.getServerWorld().spawnEntity(impostor),
                        "failed to spawn SETTLE aggregate impostor");
                ItemEntity nearerSpoil = new ItemEntity(
                        bot.getServerWorld(), face.getX() + 0.5D, face.getY() + 0.25D,
                        face.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL));
                nearerSpoil.setVelocity(Vec3d.ZERO);
                nearerSpoil.setPickupDelayInfinite();
                require(context, bot.getServerWorld().spawnEntity(nearerSpoil),
                        "failed to spawn nearer SETTLE untracked spoil");
                escaped.refreshPositionAndAngles(
                        entry.getX() + 0.5D, entry.getY() + 0.25D,
                        entry.getZ() + 0.5D, 0.0F, 0.0F);
                escaped.setVelocity(Vec3d.ZERO);
                escaped.setPickupDelayInfinite();
                Map<String, String> interrupted = task.checkpoint();
                require(context, "false".equals(
                                interrupted.get("pocket_ledger_verified"))
                                && MiningServiceTask.inspectCheckpoint(interrupted).isPresent(),
                        "SETTLE in-flight checkpoint retained stale verification: "
                                + interrupted);
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), interrupted, policy,
                        0, "settle-tracked-escape", 0, cursor);
                active[0].start(bot);
                require(context, active[0].state() == TaskState.RUNNING
                                && "SETTLE_DISPOSABLE".equals(
                                active[0].checkpoint().get("phase")),
                        "SETTLE in-flight checkpoint did not restart in place: "
                                + active[0].state() + ":" + active[0].failureReason());
                escapedRef.set(escaped);
                impostorRef.set(impostor);
                nearerSpoilRef.set(nearerSpoil);
                injected.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("SETTLE tracked escape ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_disposal_settle_timeout"
                            .equals(task.failureReason()),
                    "SETTLE in-flight identity propagated the wrong failure: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                    "SETTLE in-flight timeout failed before double seal");
            require(context, MiningServiceTask.inspectCheckpoint(task.checkpoint()).isPresent(),
                    "SETTLE timeout checkpoint lost restore authority");
            escapedRef.get().discard();
            impostorRef.get().discard();
            nearerSpoilRef.get().discard();
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceTrackedEscapeStrict", tickLimit = 500)
    public void sealPhaseTrackedEscapeFailsTypedAndCannotHideBehindNearerSpoil(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceTrackedEscapeGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "tracked-escape", 0, cursor);
        original.start(bot);
        MiningServiceTask[] active = {original};
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<ItemEntity> escapedRef = new AtomicReference<>();
        AtomicReference<ItemEntity> impostorRef = new AtomicReference<>();
        AtomicReference<ItemEntity> nearerSpoilRef = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!injected.get()) {
                if (task.state() != TaskState.RUNNING) {
                    context.throwGameTestException(
                            "tracked-escape fixture ended before injection: "
                                    + task.state() + ":" + task.failureReason());
                }
                if (!"SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                        || live.getOrDefault("pocket_ledger", "").isBlank()) {
                    return;
                }
                String[] identities = live.getOrDefault("pocket_entities", "")
                        .split(",", -1);
                require(context, identities.length >= 1
                                && java.util.Arrays.stream(identities)
                                .noneMatch(String::isBlank),
                        "tracked-escape fixture did not commit valid identities: " + live);
                ItemEntity escaped = java.util.Arrays.stream(identities)
                        .map(java.util.UUID::fromString)
                        .map(id -> bot.getServerWorld().getEntity(id))
                        .filter(ItemEntity.class::isInstance)
                        .map(ItemEntity.class::cast)
                        .filter(ItemEntity::isAlive)
                        .findFirst()
                        .orElse(null);
                require(context, escaped != null,
                        "all committed tracked entities vanished before escape injection");
                ItemEntity impostor = new ItemEntity(
                        bot.getServerWorld(), sink.getX() + 0.5D, sink.getY() + 0.25D,
                        sink.getZ() + 0.5D, escaped.getStack().copy());
                impostor.setVelocity(Vec3d.ZERO);
                impostor.setPickupDelayInfinite();
                require(context, bot.getServerWorld().spawnEntity(impostor),
                        "failed to spawn aggregate-only sink impostor");
                ItemEntity nearerSpoil = new ItemEntity(
                        bot.getServerWorld(), face.getX() + 0.5D, face.getY() + 0.25D,
                        face.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL));
                nearerSpoil.setVelocity(Vec3d.ZERO);
                nearerSpoil.setPickupDelayInfinite();
                require(context, bot.getServerWorld().spawnEntity(nearerSpoil),
                        "failed to spawn nearer untracked opening spoil");
                escaped.refreshPositionAndAngles(
                        entry.getX() + 0.5D, entry.getY() + 0.25D,
                        entry.getZ() + 0.5D, 0.0F, 0.0F);
                escaped.setVelocity(Vec3d.ZERO);
                escaped.setPickupDelayInfinite();
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live, policy,
                        0, "tracked-escape", 0, cursor);
                active[0].start(bot);
                escapedRef.set(escaped);
                impostorRef.set(impostor);
                nearerSpoilRef.set(nearerSpoil);
                injected.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("tracked escape ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, injected.get()
                            && "mining_service_disposal_tracked_entity_escaped"
                            .equals(task.failureReason()),
                    "SEAL tracked escape did not preserve its exact typed failure: "
                            + task.failureReason());
            require(context, isSolid(bot, entry) && isSolid(bot, entry.up()),
                    "SEAL tracked escape failed before factual double seal");
            Map<String, String> terminal = task.checkpoint();
            require(context, MiningServiceTask.inspectCheckpoint(terminal).isPresent()
                            && !terminal.getOrDefault("pocket_ledger", "").isBlank()
                            && !terminal.getOrDefault("pocket_entities", "").isBlank(),
                    "tracked escape lost its durable ledger/UUID debt: " + terminal);
            escapedRef.get().discard();
            impostorRef.get().discard();
            nearerSpoilRef.get().discard();
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 500)
    public void settleTimeoutSealsBothMouthCellsBeforeTypedFailure(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceDebtTimeoutGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        // Keep the two seals in a different, non-disposable item so the complete dirt stack can
        // become a baseline-zero ledger=64 transaction.
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERRACK, 2));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 2,
                "missing-identity fixture did not begin with exactly two free slots");
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "debt-timeout", 0, cursor)};
        active[0].start(bot);
        AtomicBoolean restoredWithMissingIdentity = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            MiningServiceTask task = active[0];
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restoredWithMissingIdentity.get()
                    && "SETTLE_DISPOSABLE".equals(live.get("phase"))
                    && "minecraft:dirt=64".equals(live.get("pocket_ledger"))) {
                require(context, live.getOrDefault("pocket_baseline", "").isBlank(),
                        "missing-identity fixture did not preserve baseline=0: " + live);
                String realEntities = live.getOrDefault("pocket_entities", "");
                require(context, !realEntities.isBlank()
                                && !live.getOrDefault("pocket_lineage", "").isBlank(),
                        "committed dirt ledger had no persisted lineage root");
                java.util.UUID real = java.util.UUID.fromString(
                        realEntities.split(",", -1)[0]);
                require(context, bot.getServerWorld().getEntity(real) instanceof ItemEntity,
                        "committed dirt lineage root vanished before interruption");
                bot.getServerWorld().getEntity(real).discard();
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "factual pre-loss checkpoint stopped decoding");
                task.abort(bot);
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live,
                        MiningServiceTask.ServicePolicy.defaultOre(false),
                        0, "debt-timeout", 0, cursor);
                active[0].start(bot);
                restoredWithMissingIdentity.set(true);
                return;
            }
            task = active[0];
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("settle-timeout debt ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, restoredWithMissingIdentity.get(),
                    "fixture never restored a ledger with one missing UUID");
            require(context, "mining_service_disposal_settle_timeout"
                            .equals(task.failureReason()),
                    "settle timeout propagated the wrong typed failure: "
                            + task.failureReason());
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "settle timeout failed before both mouth seals were factual");
            Map<String, String> terminal = task.checkpoint();
            require(context, MiningServiceTask.inspectCheckpoint(terminal).isPresent()
                            && Integer.parseInt(terminal.get("budget_used")) > 0,
                    "failed sealed debt lost its restartable hard-budget checkpoint: " + terminal);
            require(context, terminal.getOrDefault("pocket_baseline", "").isBlank(),
                    "baseline-zero missing-UUID debt mutated its baseline: " + terminal);
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 700)
    public void consecutiveSameFaceDisposalsUseIncrementalSinkBaseline(TestContext context) {
        Fixture fixture = spawn(context, "MiningServicePocketReuseGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask[] active = {new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                0, "pocket-reuse", 0, cursor)};
        active[0].start(bot);
        int[] completed = {0};
        int[] secondCompletedAt = {-1};
        int[] ticks = {0};

        context.runAtEveryTick(() -> {
            ticks[0]++;
            if (active[0].state() == TaskState.RUNNING) {
                active[0].tick(bot);
            }
            if (active[0].state() == TaskState.FAILED
                    || active[0].state() == TaskState.CANCELLED) {
                context.throwGameTestException("same-face disposal " + completed[0]
                        + " ended as " + active[0].state() + ":"
                        + active[0].failureReason());
            }
            if (active[0].state() != TaskState.COMPLETED) {
                return;
            }
            if (completed[0] == 0) {
                require(context, sinkCount(bot, sink, Items.DIRT) >= 62,
                        "first same-face disposal never reached the sink");
                InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
                require(context, freeMainSlots(bot) == 3,
                        "second same-face fixture did not refill exactly one slot");
                completed[0] = 1;
                active[0] = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                        0, "pocket-reuse", 0, cursor);
                active[0].start(bot);
                return;
            }
            if (secondCompletedAt[0] < 0) {
                secondCompletedAt[0] = ticks[0];
            }
            if (ticks[0] - secondCompletedAt[0] <= 100) {
                return;
            }
            require(context, sinkCount(bot, sink, Items.DIRT) >= 62,
                    "second service reopened and re-collected the first sink ledger");
            require(context, sinkCount(bot, sink, Items.ANDESITE) >= 62,
                    "second service accepted the old baseline without its new ledger increment");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && InventoryAction.countItem(bot, Items.ANDESITE) == 0,
                    "same-face sink contents returned after vanilla pickup delay expired");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "second same-face service failed to reseal the reused mouth");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceFullPocketReuseStrict", tickLimit = 900)
    public void fullInventoryReusedPocketFreesAStackBeforeCollectingOpeningSpoil(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceFullPocketGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 0,
                "full-pocket fixture did not begin with zero free slots");

        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos sink = face.east(2);
        prepareDisposalPocket(fixture, Direction.EAST);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "full-pocket-reuse", 0, cursor);
        task.start(bot);
        int[] completedAt = {-1};
        int[] dirtAtCompletion = {-1};
        int[] andesiteAtCompletion = {-1};
        int[] gravelAtCompletion = {-1};
        int[] sandAtCompletion = {-1};
        int[] ticks = {0};
        boolean[] prebaselineIdentityReset = {false};

        context.runAtEveryTick(() -> {
            ticks[0]++;
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!prebaselineIdentityReset[0]
                    && "DROP_DISPOSABLE".equals(live.get("phase"))
                    && "true".equals(live.get("pocket_drop_committed"))
                    && live.getOrDefault("pocket_ledger", "").isBlank()) {
                require(context, !live.getOrDefault("pocket_baseline", "").isBlank(),
                        "full-pocket fixture never froze its tracked prebaseline drop");
                require(context, !live.getOrDefault("pocket_entities", "").isBlank(),
                        "frozen baseline UUID lineage was not persisted for merge attestation");
                prebaselineIdentityReset[0] = true;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("full-pocket disposal ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            if (completedAt[0] < 0) {
                completedAt[0] = ticks[0];
                dirtAtCompletion[0] = InventoryAction.countItem(bot, Items.DIRT);
                andesiteAtCompletion[0] = InventoryAction.countItem(bot, Items.ANDESITE);
                gravelAtCompletion[0] = InventoryAction.countItem(bot, Items.GRAVEL);
                sandAtCompletion[0] = InventoryAction.countItem(bot, Items.SAND);
                // Every disposable stack must be absent at completion. A one- or two-item dirt
                // remainder still occupies the fourth promised working slot even if it originated
                // from ordinary opening spoil and could otherwise be spent on the double seal.
                require(context, dirtAtCompletion[0] == 0
                                && andesiteAtCompletion[0] == 0
                                && gravelAtCompletion[0] == 0
                                && sandAtCompletion[0] == 0,
                        "full-pocket service retained a disposable stack at completion");
            }
            if (ticks[0] - completedAt[0] <= 100) {
                return;
            }
            require(context, freeMainSlots(bot) >= 4,
                    "full-pocket service completed below its four-slot postcondition");
            require(context, prebaselineIdentityReset[0],
                    "full-pocket service skipped the prebaseline identity reset boundary");
            require(context, InventoryAction.countItem(bot, Items.DIRT)
                            == dirtAtCompletion[0]
                            && InventoryAction.countItem(bot, Items.ANDESITE)
                            == andesiteAtCompletion[0]
                            && InventoryAction.countItem(bot, Items.GRAVEL)
                            == gravelAtCompletion[0]
                            && InventoryAction.countItem(bot, Items.SAND)
                            == sandAtCompletion[0],
                    "opening spoil or disposable stacks returned after completion");
            require(context, sinkCount(bot, sink, Items.DIRT) >= 64
                            && sinkCount(bot, sink, Items.GRAVEL) >= 64
                            && sinkCount(bot, sink, Items.SAND) >= 64
                            && sinkCount(bot, sink, Items.ANDESITE) >= 62,
                    "zero-slot service did not physically settle every disposal ledger increment");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "zero-slot service did not double-seal the reused mouth");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceOpeningSpoilStrict", tickLimit = 900)
    public void nonWhitelistedNaturalWorkFaceSpoilCannotConsumePromisedSlot(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceClaySpoilGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.ANDESITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.SAND, 64));
        // Keep one additional non-seal junk stack available after the initial four-slot repair.
        // The late clay stack then has a physically legal capacity-recovery path instead of
        // correctly forcing the service to seal and fail for lack of anything disposable.
        InventoryAction.giveItem(bot, new ItemStack(Items.GRAVEL, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRANITE, 64));
        for (int index = 0; index < 28; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        require(context, freeMainSlots(bot) == 0,
                "clay-spoil fixture did not begin with a full main inventory");

        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        BlockPos entry = face.east();
        BlockPos sink = face.east(2);
        // Use a non-disposable opening material so its delayed baseline entity cannot merge with
        // a later tracked junk UUID and turn this spoil-capacity test into an identity-merge race.
        for (BlockPos cell : new BlockPos[]{entry, entry.up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "non-junk-opening-spoil", 0, cursor);
        task.start(bot);
        AtomicReference<ItemEntity> claySpoil = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            Map<String, String> before = task.checkpoint();
            if (task.state() == TaskState.RUNNING && claySpoil.get() == null
                    && "SEAL_DISPOSAL_POCKET".equals(before.get("phase"))) {
                // Four clay balls are the ordinary non-whitelisted drop of one clay block. Hold
                // them in the factual work-face corridor long enough to prove that sealing waits
                // for collection instead of publishing a free-slot promise first.
                ItemEntity spoil = new ItemEntity(
                        bot.getServerWorld(), face.getX() + 0.5D, face.getY() + 0.25D,
                        face.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL, 4));
                spoil.setVelocity(Vec3d.ZERO);
                spoil.setPickupDelay(20);
                require(context, bot.getServerWorld().spawnEntity(spoil),
                        "failed to spawn controlled natural clay opening spoil");
                claySpoil.set(spoil);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("non-junk opening spoil ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, claySpoil.get() != null,
                    "fixture never reached the pre-seal spoil injection boundary");
            require(context, !claySpoil.get().isAlive()
                            && InventoryAction.countItem(bot, Items.CLAY_BALL) == 4,
                    "service sealed before collecting non-whitelisted work-face spoil");
            require(context, freeMainSlots(bot) >= 4,
                    "collected clay spoil consumed a promised post-service free slot");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up()),
                    "clay-spoil service did not leave a factual double seal");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 900)
    public void disposalPocketPreservesObservedOreAndRestartsThroughOppositeSide(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServicePocketOreGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        bot.getServerWorld().setBlockState(
                face.east(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        AtomicReference<MiningServiceTask> active = new AtomicReference<>(new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                policy, 0, "pocket-ore", 0, cursor));
        active.get().start(bot);
        AtomicBoolean restarted = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active.get();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!restarted.get()
                    && "OPEN_DISPOSAL_POCKET".equals(live.get("phase"))
                    && "WEST".equals(live.get("pocket_direction"))
                    && live.getOrDefault("pocket_failure", "")
                    .startsWith("retry_disposal_ore:EAST:")) {
                require(context, MiningServiceTask.inspectCheckpoint(live).isPresent(),
                        "ore-reroute checkpoint did not decode: " + live);
                Map<String, String> staleDirection = new LinkedHashMap<>(live);
                staleDirection.put("pocket_failure",
                        "retry_disposal_ore:WEST:mining_service_disposal_ore_preserved:"
                                + "minecraft:diamond_ore");
                require(context, MiningServiceTask.inspectCheckpoint(staleDirection).isEmpty(),
                        "ore-reroute checkpoint accepted its active side as the rejected side");
                Map<String, String> malformedDirection = new LinkedHashMap<>(live);
                malformedDirection.put("pocket_failure",
                        "retry_disposal_ore:UP:mining_service_disposal_ore_preserved:"
                                + "minecraft:diamond_ore");
                require(context, MiningServiceTask.inspectCheckpoint(malformedDirection).isEmpty(),
                        "ore-reroute checkpoint accepted a non-lateral rejected direction");
                require(context, isSolid(bot, face.east())
                                && isSolid(bot, face.east().up()),
                        "preferred ore pocket was not physically sealed before reroute");
                require(context, bot.getServerWorld().getBlockState(face.east(2))
                                .isOf(Blocks.DIAMOND_ORE),
                        "preferred pocket mined or replaced its finite ore");
                int budgetBefore = Integer.parseInt(live.get("budget_used"));
                task.abort(bot);
                MiningServiceTask restored = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), live,
                        policy, 0, "pocket-ore", 0, cursor);
                restored.start(bot);
                require(context, Integer.parseInt(restored.checkpoint().get("budget_used"))
                                >= budgetBefore,
                        "ore-reroute restart reset the hard budget");
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("ore-reroute disposal ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(),
                    "ore pocket completed without exercising restartable reroute state");
            require(context, bot.getBlockPos().equals(face),
                    "ore-reroute disposal did not finish at the exact work face");
            require(context, bot.getServerWorld().getBlockState(face.east(2))
                            .isOf(Blocks.DIAMOND_ORE),
                    "ore-reroute disposal changed the finite ore block");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up())
                            && isSolid(bot, face.west()) && isSolid(bot, face.west().up()),
                    "ore-reroute disposal did not seal both attempted mouths");
            require(context, sinkCount(bot, face.west(2), Items.DIRT) > 0,
                    "opposite pocket did not retain the physical disposal ledger");
            require(context, InventoryAction.countItem(bot, Items.GLASS) == 30 * 64,
                    "ore-reroute disposal changed protected inventory");
            require(context, freeMainSlots(bot) >= 4,
                    "ore-reroute disposal completed below its free-slot contract");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceBothPocketOreStrict", tickLimit = 700)
    public void disposalPocketPreservesBothOreSidesAndFailsAfterDoubleSeal(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceBothPocketOreGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        for (BlockPos mouth : new BlockPos[]{face.east(), face.west()}) {
            bot.getServerWorld().setBlockState(
                    mouth, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
            bot.getServerWorld().setBlockState(
                    mouth.up(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        }
        bot.getServerWorld().setBlockState(
                face.east(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.west(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "both-pocket-ore", 0, cursor);
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("dual-ore disposal ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                            .equals(task.failureReason()),
                    "dual-ore disposal failed with the wrong typed reason: "
                            + task.failureReason());
            require(context, bot.getServerWorld().getBlockState(face.east(2))
                            .isOf(Blocks.DIAMOND_ORE)
                            && bot.getServerWorld().getBlockState(face.west(2))
                            .isOf(Blocks.DIAMOND_ORE),
                    "dual-ore disposal changed one of the finite ore blocks");
            require(context, isSolid(bot, face.east()) && isSolid(bot, face.east().up())
                            && isSolid(bot, face.west()) && isSolid(bot, face.west().up()),
                    "dual-ore disposal published failure before sealing both attempted mouths");
            Map<String, String> terminal = task.checkpoint();
            require(context, "PREPARE".equals(terminal.get("phase"))
                            && task.failureReason().equals(
                            terminal.get("terminal_failure"))
                            && terminal.keySet().stream().noneMatch(
                            key -> key.startsWith("pocket_"))
                            && Integer.parseInt(terminal.get("budget_used")) > 0
                            && MiningServiceTask.inspectCheckpoint(terminal)
                            .filter(metadata -> task.failureReason().equals(
                            metadata.terminalFailure())).isPresent(),
                    "settled dual-ore failure retained physical pocket authority: " + terminal);

            Map<String, String> legacy = new LinkedHashMap<>(terminal);
            legacy.remove("terminal_failure");
            require(context, MiningServiceTask.inspectCheckpoint(legacy).isPresent(),
                    "optional terminal receipt broke current schema-8 checkpoints");
            Map<String, String> emptyReceipt = new LinkedHashMap<>(terminal);
            emptyReceipt.put("terminal_failure", "");
            require(context, MiningServiceTask.inspectCheckpoint(emptyReceipt).isEmpty(),
                    "decoder accepted an empty terminal failure receipt");
            Map<String, String> committedReceipt = new LinkedHashMap<>(terminal);
            committedReceipt.put("phase", "DONE");
            committedReceipt.put("budget_used", "0");
            committedReceipt.put("last_progress_budget", "0");
            require(context, MiningServiceTask.inspectCheckpoint(committedReceipt).isEmpty(),
                    "decoder accepted DONE plus a terminal failure receipt");
            for (String pocketKey : Set.of(
                    "pocket_entry", "pocket_sink", "pocket_direction",
                    "pocket_entities", "pocket_lineage", "pocket_baseline",
                    "pocket_ledger", "pocket_drop_committed",
                    "pocket_ledger_verified", "pocket_phase_started",
                    "pocket_failure", "pocket_clear_index")) {
                Map<String, String> conflictingReceipt = new LinkedHashMap<>(terminal);
                conflictingReceipt.put(pocketKey, "residual-pocket-authority");
                require(context,
                        MiningServiceTask.inspectCheckpoint(conflictingReceipt).isEmpty(),
                        "decoder accepted terminal receipt plus lone " + pocketKey);
            }

            int dirtBeforeRestore = InventoryAction.countItem(bot, Items.DIRT);
            BlockPos rememberedFaceBeforeRestore = face.north(4);
            BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace(
                    "mine_face", bot.getServerWorld(), rememberedFaceBeforeRestore);
            MiningServiceTask restored = new MiningServiceTask(
                    Set.of(Blocks.DIAMOND_ORE), terminal,
                    MiningServiceTask.ServicePolicy.defaultOre(false),
                    0, "both-pocket-ore", 0, cursor);
            restored.start(bot);
            Map<String, String> replayed = restored.checkpoint();
            require(context, restored.state() == TaskState.FAILED
                            && task.failureReason().equals(restored.failureReason())
                            && task.failureReason().equals(
                            replayed.get("terminal_failure"))
                            && replayed.keySet().stream().noneMatch(
                            key -> key.startsWith("pocket_"))
                            && terminal.get("budget_used").equals(
                            replayed.get("budget_used"))
                            && InventoryAction.countItem(bot, Items.DIRT)
                            == dirtBeforeRestore
                            && BotMemoryStore.INSTANCE.of(bot.getUuid())
                            .placeIn(bot.getServerWorld(), "mine_face")
                            .filter(rememberedFaceBeforeRestore::equals).isPresent(),
                    "restart replayed settled disposal work instead of the terminal result: "
                            + restored.state() + ":" + restored.failureReason() + " " + replayed);
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketEntryOreStrict", tickLimit = 700)
    public void secondPocketEntryOreRetiresOnlyAfterVisibleDoubleClosure(
            TestContext context) {
        runSecondPocketMouthOreRetirement(
                context, "MiningServicePocketEntryOreGT", false);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketUpperOreStrict", tickLimit = 700)
    public void secondPocketUpperOreRetiresOnlyAfterVisibleDoubleClosure(
            TestContext context) {
        runSecondPocketMouthOreRetirement(
                context, "MiningServicePocketUpperOreGT", true);
    }

    private static void runSecondPocketMouthOreRetirement(TestContext context,
                                                           String name,
                                                           boolean upperOre) {
        Fixture fixture = spawn(context, name, false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        // EAST is the deterministic first pocket and forces the one allowed reroute at its sink.
        // Use no-drop glass at that mouth so this fixture proves only the second pocket's
        // mouth-ore retirement. A dirt drop still inside vanilla's pickup-delay window is real
        // unresolved opening spoil and must continue to block terminal receipt publication.
        bot.getServerWorld().setBlockState(
                face.east(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.east().up(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.east(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos preserved = upperOre ? face.west().up() : face.west();
        BlockPos complementaryMouth = upperOre ? face.west() : face.west().up();
        bot.getServerWorld().setBlockState(
                preserved, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                complementaryMouth,
                upperOre ? Blocks.GLASS.getDefaultState() : Blocks.AIR.getDefaultState(),
                Block.NOTIFY_ALL);

        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        String mission = upperOre ? "upper-mouth-ore" : "entry-mouth-ore";
        AtomicReference<MiningServiceTask> active = new AtomicReference<>(
                new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE), Map.of(),
                        policy, 0, mission, 0, cursor));
        active.get().start(bot);
        AtomicBoolean restarted = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MiningServiceTask task = active.get();
            Map<String, String> before = task.checkpoint();
            if (!restarted.get() && task.state() == TaskState.RUNNING
                    && "SEAL_DISPOSAL_POCKET".equals(before.get("phase"))
                    && "WEST".equals(before.get("pocket_direction"))
                    && "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                    .equals(before.get("pocket_failure"))) {
                require(context, String.valueOf(upperOre ? 1 : 0)
                                .equals(before.get("pocket_clear_index"))
                                && MiningServiceTask.inspectCheckpoint(before).isPresent(),
                        "mouth-ore frontier checkpoint lost its exact clear index: " + before);
                int budget = Integer.parseInt(before.get("budget_used"));
                task.abort(bot);
                MiningServiceTask restored = new MiningServiceTask(
                        Set.of(Blocks.DIAMOND_ORE), before,
                        policy, 0, mission, 0, cursor);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING
                                && Integer.parseInt(
                                restored.checkpoint().get("budget_used")) >= budget,
                        "mouth-ore restart reset its physical seal authority");
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(
                        "mouth-ore disposal ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            Map<String, String> terminal = task.checkpoint();
            require(context, restarted.get()
                            && "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                            .equals(task.failureReason())
                            && task.failureReason().equals(
                            terminal.get("terminal_failure"))
                            && terminal.keySet().stream().noneMatch(
                            key -> key.startsWith("pocket_"))
                            && MiningServiceTask.inspectCheckpoint(terminal)
                            .filter(metadata -> task.failureReason().equals(
                            metadata.terminalFailure())).isPresent(),
                    "closed mouth-ore failure retained or lost terminal authority: "
                            + terminal);
            require(context, bot.getServerWorld().getBlockState(preserved)
                            .isOf(Blocks.DIAMOND_ORE)
                            && isSolid(bot, face.west())
                            && isSolid(bot, face.west().up())
                            && isSolid(bot, face.east())
                            && isSolid(bot, face.east().up()),
                    "mouth-ore retirement changed its finite ore or skipped double closure");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceClosedOreEntityDebtStrict", tickLimit = 500)
    public void preservedOreFailureKeepsPocketIdentityWhenSinkEntityRemains(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceClosedOreEntityDebtGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        for (BlockPos mouth : new BlockPos[]{face.east(), face.west()}) {
            bot.getServerWorld().setBlockState(
                    mouth, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
            bot.getServerWorld().setBlockState(
                    mouth.up(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        }
        bot.getServerWorld().setBlockState(
                face.east(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.west(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.west(2).up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "closed-ore-entity-debt", 0, cursor);
        task.start(bot);
        AtomicReference<ItemEntity> residual = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            Map<String, String> before = task.checkpoint();
            if (task.state() == TaskState.RUNNING && residual.get() == null
                    && "SEAL_DISPOSAL_POCKET".equals(before.get("phase"))
                    && "WEST".equals(before.get("pocket_direction"))
                    && "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                    .equals(before.get("pocket_failure"))) {
                BlockPos sink = face.west(2);
                ItemEntity item = new ItemEntity(
                        bot.getServerWorld(), sink.getX() + 0.5D, sink.getY() + 1.25D,
                        sink.getZ() + 0.5D, new ItemStack(Items.CLAY_BALL, 4));
                item.setVelocity(Vec3d.ZERO);
                item.setNoGravity(true);
                item.setPickupDelayInfinite();
                require(context, bot.getServerWorld().spawnEntity(item),
                        "failed to spawn controlled terminal sink entity");
                require(context, ObservableWorldQuery.canObserveEntity(bot, item),
                        "controlled terminal sink entity was not strictly observable");
                residual.set(item);
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("entity-debt ore disposal ended as "
                        + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, residual.get() != null && residual.get().isAlive(),
                    "entity-debt fixture failed before retaining its sink entity");
            require(context, "mining_service_disposal_ore_preserved:minecraft:diamond_ore"
                            .equals(task.failureReason()),
                    "entity-debt disposal changed the typed ore failure: "
                            + task.failureReason());
            Map<String, String> terminal = task.checkpoint();
            require(context, "SEAL_DISPOSAL_POCKET".equals(terminal.get("phase"))
                            && terminal.containsKey("pocket_entry")
                            && terminal.containsKey("pocket_sink")
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "entity-debt disposal retired an observable unowned sink entity: "
                            + terminal);
            require(context, isSolid(bot, face.west()) && isSolid(bot, face.west().up()),
                    "entity-debt disposal failed before double-sealing its terminal mouth");
            residual.get().discard();
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceSealLossFiniteStrict", tickLimit = 500)
    public void disposalOreSealLossTerminatesWithinPocketRecoveryWindow(
            TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceSealLossGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        for (int index = 0; index < 30; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64));
        }
        BlockPos face = bot.getBlockPos().toImmutable();
        prepareDisposalPocket(fixture, Direction.EAST);
        prepareDisposalPocket(fixture, Direction.WEST);
        // Glass opens without producing a delayed disposable block drop that could replenish the
        // deliberately removed seal inventory after the retry marker is checkpointed.
        bot.getServerWorld().setBlockState(
                face.east(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.east().up(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                face.east(2), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        MiningCursor cursor = miningCursor(face, 0, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.defaultOre(false),
                0, "pocket-seal-loss", 0, cursor);
        task.start(bot);
        AtomicBoolean sealInventoryRemoved = new AtomicBoolean();
        int[] removedAt = {-1};
        int[] ticks = {0};

        context.runAtEveryTick(() -> {
            ticks[0]++;
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            Map<String, String> live = task.checkpoint();
            if (!sealInventoryRemoved.get()
                    && "SEAL_DISPOSAL_POCKET".equals(live.get("phase"))
                    && live.getOrDefault("pocket_failure", "")
                    .startsWith("retry_disposal_ore:EAST:")) {
                int dirt = InventoryAction.countItem(bot, Items.DIRT);
                require(context, dirt > 0 && InventoryAction.removeItems(bot, Items.DIRT, dirt),
                        "seal-loss fixture could not remove its live seal inventory");
                sealInventoryRemoved.set(true);
                removedAt[0] = ticks[0];
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("seal-loss disposal ended as " + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, sealInventoryRemoved.get(),
                    "seal-loss disposal failed before exercising retry-marker recovery");
            require(context, "mining_service_disposal_seal_block_missing"
                            .equals(task.failureReason()),
                    "seal-loss disposal failed with the wrong typed reason: "
                            + task.failureReason());
            require(context, ticks[0] - removedAt[0] <= 110,
                    "seal-loss disposal exceeded its bounded pocket recovery window: elapsed="
                            + (ticks[0] - removedAt[0]));
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 240)
    public void nearlyBrokenTunnelingToolsDoNotBypassDurabilityService(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceDamagedToolsGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 28));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        for (int i = 0; i < 4; i++) {
            ItemStack damaged = new ItemStack(Items.STONE_PICKAXE);
            damaged.setDamage(damaged.getMaxDamage() - 1);
            InventoryAction.giveItem(bot, damaged);
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), true);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("damaged channel-tool service ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            long fresh = bot.getInventory().main.stream()
                    .filter(stack -> stack.isOf(Items.STONE_PICKAXE) && stack.getDamage() == 0)
                    .count();
            require(context, fresh == 4,
                    "four nearly-broken picks incorrectly satisfied the durability target: fresh="
                            + fresh);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 16,
                    "durability repair consumed the emergency stone reserve");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPreflightUsesItsOwnProfileAndValidatesTheExactKit(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianPreflightGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 52);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32));
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("exact obsidian preflight kit was rejected: "
                        + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, "OBSIDIAN_PREFLIGHT".equals(
                            task.checkpoint().get("service_profile")),
                    "preflight checkpoint lost its distinct profile: " + task.checkpoint());
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.COBBLESTONE) == 52
                            && InventoryAction.countItem(bot, Items.STICK) == 24
                            && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                    "preflight consumed its water or emergency-block reserve");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPreflightFailsTypedWithoutItsWaterBucket(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianPreflightWaterGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 52);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32));
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("missing water bucket bypassed preflight");
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_water_bucket_missing".equals(task.failureReason()),
                    "missing preflight water failed with the wrong reason: "
                            + task.failureReason());
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void thirtyTwoObsidianServiceHorizonFundsAllFourWorstCaseRepairs(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianHorizonGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 0, 64);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 32));
        giveExhaustedStonePicks(bot, 4);

        runServiceToTerminal(new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32)), bot);
        assertServiceResources(context, bot, 52, 24, "preflight");

        exhaustAllStonePicks(bot);
        runServiceToTerminal(new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8), bot);
        assertServiceResources(context, bot, 40, 16, "boundary_8");

        exhaustAllStonePicks(bot);
        runServiceToTerminal(new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 16), 16), bot);
        assertServiceResources(context, bot, 28, 8, "boundary_16");

        exhaustAllStonePicks(bot);
        runServiceToTerminal(new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24), bot);
        assertServiceResources(context, bot, 16, 0, "boundary_24");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPreflightFailsTypedWithoutCarriedCraftingTable(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianTableGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 52);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        InventoryAction.removeItems(bot, Items.CRAFTING_TABLE, 1);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32));
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("missing crafting table bypassed preflight");
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, "mining_service_crafting_table_missing"
                            .equals(task.failureReason()),
                    "missing crafting table failed with the wrong reason: "
                            + task.failureReason());
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPolicyRejectsEightRawDurability(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianRaw8GT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 8, 4, 16);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        for (int tick = 0; tick < 400 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "mining_service_target_tool_durability_depleted:"),
                "raw remaining=8 failed for the wrong reason: "
                        + task.state() + ":" + task.failureReason());
        require(context, diamondRawDurability(bot) == 8,
                "failed durability service damaged or replaced the guarded pick");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPolicyAcceptsNineRawDurability(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianRaw9GT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 9, 4, 16);
        MiningServiceTask task = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        for (int tick = 0; tick < 400 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.COMPLETED,
                "raw remaining=9 was rejected: "
                        + task.state() + ":" + task.failureReason());
        require(context, diamondRawDurability(bot) == 9,
                "accepted service consumed target-tool durability");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPolicyWillNotSpendTheLastSixteenStoneLikeBlocks(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianReserveGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 0, 27);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("insufficient stone surplus bypassed reserve gate");
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith(
                            "mining_service_channel_material_reserve_depleted:"),
                    "stone reserve failed for the wrong reason: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 27,
                    "failed service consumed protected stone-like blocks");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPolicyRejectsMissingRepairSticksBeforeConsumingStone(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianStickGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 0, 28);
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 7));

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("missing repair sticks bypassed reserve gate");
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith(
                            "mining_service_tool_stick_reserve_depleted:"),
                    "stick reserve failed for the wrong reason: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 28,
                    "failed stick gate consumed stone before proving the recipe");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void obsidianPolicyRoundTripsAndCannotRestoreAsDefaultOrePolicy(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianPolicyGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 16);

        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        original.start(bot);
        Map<String, String> checkpoint = original.checkpoint();
        MiningServiceTask.RestoreMetadata metadata =
                MiningServiceTask.inspectCheckpoint(checkpoint).orElseThrow();
        require(context, "8".equals(checkpoint.get("schema"))
                        && "OBSIDIAN_8".equals(checkpoint.get("service_profile"))
                        && "standalone".equals(checkpoint.get("service_mission_id"))
                        && "32".equals(checkpoint.get("service_target_count"))
                        && "24".equals(checkpoint.get("service_boundary"))
                        && "8".equals(checkpoint.get("target_tool_usable"))
                        && "520".equals(checkpoint.get("channel_tool_usable"))
                        && "0".equals(checkpoint.get("torch_min_count"))
                        && "0".equals(checkpoint.get("future_stick_reserve"))
                        && "true".equals(checkpoint.get("crafting_table_required"))
                        && metadata.policy().equals(
                        MiningServiceTask.ServicePolicy.obsidian8(32, 24))
                        && metadata.serviceBoundary() == 24,
                "schema-8 checkpoint lost its identity or policy: " + checkpoint);

        Map<String, String> schema4 = new LinkedHashMap<>(checkpoint);
        schema4.put("schema", "4");
        schema4.remove("torch_min_count");
        schema4.remove("service_dimension");
        require(context, MiningServiceTask.inspectCheckpoint(Map.copyOf(schema4)).isPresent(),
                "schema-4 obsidian identity stopped decoding after schema-8 upgrade: " + schema4);

        MiningServiceTask wrongPolicy = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), checkpoint, false);
        wrongPolicy.start(bot);
        require(context, wrongPolicy.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint".equals(wrongPolicy.failureReason()),
                "obsidian checkpoint silently downgraded to default ore policy");

        Map<String, String> unidentified = new LinkedHashMap<>(checkpoint);
        unidentified.put("schema", "3");
        unidentified.remove("service_mission_id");
        unidentified.remove("service_target_count");
        unidentified.remove("service_boundary");
        unidentified.remove("future_stick_reserve");
        unidentified.remove("crafting_table_required");
        unidentified.remove("torch_min_count");
        MiningServiceTask legacyObsidian = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.copyOf(unidentified),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        legacyObsidian.start(bot);
        require(context, legacyObsidian.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint"
                        .equals(legacyObsidian.failureReason()),
                "schema-3 obsidian service retained unproven boundary authority");

        MiningServiceTask restored = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), checkpoint,
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        restored.start(bot);
        context.runAtEveryTick(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
            }
            if (restored.state() == TaskState.FAILED
                    || restored.state() == TaskState.CANCELLED) {
                context.throwGameTestException("matching policy restart failed: "
                        + restored.failureReason());
            }
            if (restored.state() == TaskState.COMPLETED) {
                cleanup(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 120)
    public void obsidianDepotPreservesDiamondBlackstoneAndSafetySupplies(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianDepositGT", true);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 0);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.BLACKSTONE, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 8));
        for (int i = 0; i < 22; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        }

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("obsidian depot service ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 3,
                    "depot deposited replacement-pick diamonds");
            require(context, InventoryAction.countItem(bot, Items.BLACKSTONE) == 16,
                    "depot deposited the emergency blackstone reserve");
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.STICK) == 8
                            && InventoryAction.countItem(bot, Items.TORCH) == 8
                            && InventoryAction.countItem(bot, Items.BREAD) == 2,
                    "depot deposited obsidian expedition safety supplies");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "depot did not unload expendable by-products");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 120)
    public void obsidianDepotReplenishesMixedEmergencyBlocksToSixteen(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianBlocksGT", true);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 5);
        InventoryAction.giveItem(bot, new ItemStack(Items.OBSIDIAN, 3));
        Inventory depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        depot.setStack(0, new ItemStack(Items.COBBLED_DEEPSLATE, 5));
        depot.setStack(1, new ItemStack(Items.BLACKSTONE, 6));
        depot.markDirty();

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("mixed emergency-block service ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                            + InventoryAction.countItem(bot, Items.BLACKSTONE) == 16,
                    "mixed depot blocks did not replenish the exact sixteen-block reserve");
            require(context, InventoryAction.countItem(bot, Items.OBSIDIAN) == 3,
                    "obsidian output left inventory during emergency-block service");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 60)
    public void obsidianServiceFailsTypedWhenEmergencyBlocksCannotReachSixteen(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceObsidianBlocksFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        giveObsidianServiceKit(bot, 33, 4, 15);

        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 24), 24);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("insufficient emergency blocks ended as "
                        + task.state());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().equals(
                            "mining_service_emergency_blocks_reserve_depleted:have=15:required=16"),
                    "insufficient emergency blocks failed with the wrong typed reason: "
                            + task.failureReason());
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void rareBoundary8AcceptsExactResourceHorizonAndRepairsChannel(TestContext context) {
        Fixture fixture = spawn(context, "RareHorizonExactGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 8);
        int preRepairSticks = rarePreRepairSticks(policy, false);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), preRepairSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("exact rare horizon failed: " + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH)
                            == policy.torchMinCount()
                            && MiningFoodReserve.units(bot.getInventory())
                            == policy.foodMinUnits(),
                    "service consumed the protected torch/food horizon");
            require(context, InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy),
                    "boundary8 did not spend exactly one current epoch's repair sticks");
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE)
                            == MiningBudget.RARE_TUNNELING_SERVICE_TARGET,
                    "boundary8 did not rebuild the seven-pick channel epoch");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void rareBoundary8RejectsOneTorchBelowHorizonBeforeRepair(TestContext context) {
        Fixture fixture = spawn(context, "RareHorizonTorchFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 8);
        int preRepairSticks = rarePreRepairSticks(policy, false);
        int haveTorches = policy.torchMinCount() - 1;
        giveRareBoundaryKit(bot, policy, haveTorches,
                policy.foodMinUnits(), preRepairSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        tickToTerminal(task, bot, 10);

        require(context, task.state() == TaskState.FAILED
                        && ("mining_service_torch_reserve_depleted:have=" + haveTorches
                        + ":required=" + policy.torchMinCount())
                        .equals(task.failureReason()),
                "one-below torch horizon failed with the wrong typed reason: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == preRepairSticks,
                "torch gate spent repair material before proving the horizon");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void rareBoundary8RejectsOneFoodBelowHorizonBeforeRepair(TestContext context) {
        Fixture fixture = spawn(context, "RareHorizonFoodFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 8);
        int preRepairSticks = rarePreRepairSticks(policy, false);
        int haveFood = policy.foodMinUnits() - 1;
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                haveFood, preRepairSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        tickToTerminal(task, bot, 10);

        require(context, task.state() == TaskState.FAILED
                        && ("mining_service_food_reserve_depleted:have=" + haveFood
                        + ":required=" + policy.foodMinUnits())
                        .equals(task.failureReason()),
                "one-below food horizon failed with the wrong typed reason: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == preRepairSticks,
                "food gate spent repair material before proving the horizon");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void rareBoundary8RejectsOneStickBelowRepairHorizon(TestContext context) {
        Fixture fixture = spawn(context, "RareHorizonStickFailGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 8);
        int requiredSticks = rarePreRepairSticks(policy, false);
        int haveSticks = requiredSticks - 1;
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), haveSticks, false);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        tickToTerminal(task, bot, 10);

        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "mining_service_tool_stick_reserve_depleted:have=" + haveSticks + ":")
                        && task.failureReason().endsWith(":required=" + requiredSticks),
                "one-below stick horizon failed with the wrong typed reason: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == haveSticks,
                "stick gate partially repaired before proving the complete horizon");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void rareBoundary8PhysicallyWithdrawsMissionHorizonFromDepot(TestContext context) {
        Fixture fixture = spawn(context, "RareHorizonDepotGT", true);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 8);
        giveRareBoundaryKit(bot, policy, 0, 0, 0, true);
        Inventory depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        int depotSlot = putStackedInventory(
                depot, 0, Items.TORCH, policy.torchMinCount());
        depotSlot = putStackedInventory(
                depot, depotSlot, Items.BREAD, policy.foodMinUnits());
        putStackedInventory(depot, depotSlot, Items.STICK, rareProtectedSticks(policy));
        depot.markDirty();

        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("depot horizon service failed: "
                        + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH)
                            == policy.torchMinCount()
                            && MiningFoodReserve.units(bot.getInventory())
                            == policy.foodMinUnits()
                            && InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy),
                    "depot did not physically fund the exact boundary8 horizon");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 1400)
    public void rareBoundary8CrowdedDepotReservesTheWholeRefillPeak(TestContext context) {
        Fixture fixture = spawn(context, "RareCrowdedDepotGT", true);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 8);
        giveRareBoundaryKit(bot, policy, 0, 0, 0, false);
        Inventory depot = ContainerAction.resolve(bot, fixture.depot()).orElseThrow();
        int depotSlot = putStackedInventory(
                depot, 0, Items.TORCH, policy.torchMinCount());
        depotSlot = putStackedInventory(
                depot, depotSlot, Items.BREAD, policy.foodMinUnits());
        putStackedInventory(depot, depotSlot, Items.STICK,
                rarePreRepairSticks(policy, false));
        depot.markDirty();
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            if (bot.getInventory().main.get(slot).isEmpty()) {
                bot.getInventory().main.set(slot, new ItemStack(Items.DIRT, 64));
            }
        }
        bot.getInventory().markDirty();
        require(context, freeMainSlots(bot) == 0,
                "crowded-depot fixture did not start full");
        prepareDisposalPocket(fixture, Direction.WEST);

        MiningServiceTask task = rareBoundaryTask(bot, 64, 8);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("crowded depot service failed: "
                        + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH)
                            == policy.torchMinCount()
                            && MiningFoodReserve.units(bot.getInventory())
                            == policy.foodMinUnits()
                            && InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy)
                            && InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == policy.emergencyBlocksReserved()
                            && InventoryAction.countItem(bot, Items.STONE_PICKAXE)
                            == MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                            && freeMainSlots(bot) >= 4,
                    "dynamic refill peak did not preserve the final four slots");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "rareServiceLocalFirst", tickLimit = 700)
    public void rareServiceUsesLocalPocketWithoutTouchingRemoteOwnedDepot(
            TestContext context) {
        Fixture fixture = spawn(context, "RareLocalFirstGT", false);
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = bot.getBlockPos().toImmutable();
        BlockPos remoteDepot = face.east(8);
        String mission = "rare-local-first";
        bot.getServerWorld().setBlockState(remoteDepot.down(),
                Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(remoteDepot,
                Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        Inventory depot = ContainerAction.resolve(bot, remoteDepot).orElseThrow();
        depot.setStack(0, new ItemStack(Items.EMERALD, 13));
        depot.setStack(1, new ItemStack(Items.GOLD_INGOT, 7));
        depot.markDirty();
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        memory.markPlace("mining_depot", bot.getServerWorld(), remoteDepot);
        memory.remember("mining_depot_owner", mission);

        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 63);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), rareProtectedSticks(policy), true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 5));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.TUFF, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GRANITE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIORITE, 64));
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == policy.emergencyBlocksReserved(),
                "offhand projection fixture retained disposable stone excess");
        require(context, bot.getInventory().offHand.getFirst().isEmpty(),
                "offhand projection fixture did not start with an empty offhand");
        bot.getInventory().offHand.set(0, new ItemStack(Items.DIRT, 2));
        while (freeMainSlots(bot) > 3) {
            int empty = firstEmptyMainSlot(bot);
            require(context, empty >= 0, "local-first fixture lost an expected empty slot");
            bot.getInventory().main.set(empty, new ItemStack(Items.GLASS, 64));
        }
        bot.getInventory().markDirty();
        prepareDisposalPocket(fixture, Direction.EAST);
        // Glass produces no fixture-only block drops. Dirt exists only in offhand, so accepting
        // all three full junk stacks proves the projection models promoteOffhandSlot, hotbar swap,
        // both physical seal consumptions, and continued cleanup after the fourth slot is safe.
        BlockPos sink = face.east(2);
        for (BlockPos cell : new BlockPos[]{face.east(), face.east().up(), sink, sink.up()}) {
            bot.getServerWorld().setBlockState(
                    cell, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        }
        MiningCursor cursor = miningCursor(face, 0, 7);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(), policy,
                63, mission, 64, cursor);
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, bot.getBlockPos().equals(face),
                    "rare local service left its exact work face: " + bot.getBlockPos());
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("rare local-first service ended as "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getServerWorld().getBlockState(remoteDepot).isOf(Blocks.CHEST)
                            && inventoryCount(depot, Items.EMERALD) == 13
                            && inventoryCount(depot, Items.GOLD_INGOT) == 7,
                    "rare local service accessed or mutated the remote owned depot");
            require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 5
                            && InventoryAction.countItem(bot, Items.DIAMOND_PICKAXE) == 1,
                    "local disposal discarded a target drop or usable damageable tool");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == policy.emergencyBlocksReserved()
                            && InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(policy),
                    "local disposal consumed protected stone or sticks");
            require(context, InventoryAction.countItem(bot, Items.TUFF) == 0
                            && InventoryAction.countItem(bot, Items.GRANITE) == 0
                            && InventoryAction.countItem(bot, Items.DIORITE) == 0
                            && sinkCount(bot, sink, Items.TUFF) == 64
                            && sinkCount(bot, sink, Items.GRANITE) == 64
                            && sinkCount(bot, sink, Items.DIORITE) == 64
                            && sinkCount(bot, sink, Items.COBBLESTONE) == 0,
                    "local disposal stopped at minimum capacity or lost a junk transaction");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "offhand-only seal blocks were not physically consumed");
            require(context, freeMainSlots(bot) >= policy.freeSlotsMin(),
                    "local disposal did not restore the delivery-slot contract");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void boundaryZeroWorstCaseRepairLeavesRetryCushionUsable(TestContext context) {
        Fixture fixture = spawn(context, "RareBoundaryZeroRetryGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy retryPolicy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 0, 1);
        // Epoch zero already spent one seven-pick pool. Epoch one receives the sealed retry heads,
        // while the sixteen emergency blocks remain untouchable.
        int retryPreRepairSticks = rarePreRepairSticks(retryPolicy, false);
        giveRareBoundaryKit(bot, retryPolicy, retryPolicy.torchMinCount(),
                retryPolicy.foodMinUnits(), retryPreRepairSticks, false);
        BlockPos face = bot.getBlockPos().toImmutable();
        MiningCursor cursor = miningCursor(face, 0, 0);
        MiningServiceTask task = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                retryPolicy,
                0, "rare-boundary-zero-retry", 64, cursor);
        task.start(bot);

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("boundary-zero retry service failed: "
                        + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.STICK)
                            == rareProtectedSticks(retryPolicy)
                            && InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == MiningBudget.EMERGENCY_STONE_LIKE
                            && InventoryAction.countItem(bot, Items.STONE_PICKAXE)
                            == MiningBudget.RARE_TUNNELING_SERVICE_TARGET,
                    "boundary-zero retry did not consume exactly its released head/stick pool");
            Map<String, String> terminal = task.checkpoint();
            require(context, terminal.containsKey("cursor_schema")
                            && terminal.get("cursor_face").equals(terminal.get("work_face"))
                            && String.valueOf(MiningBudget.EMERGENCY_STONE_LIKE)
                            .equals(terminal.get("emergency_blocks_reserved"))
                            && MiningServiceTask.inspectCheckpoint(terminal).isPresent(),
                    "completed no-pocket service did not persist its schema-8 cursor");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void boundary63AcceptsExactlyOneUsableTargetBreak(TestContext context) {
        Fixture fixture = spawn(context, "RareBoundary63ToolGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 63);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), rareProtectedSticks(policy), true);
        int ironSlot = InventoryAction.findItem(bot, Items.IRON_PICKAXE).orElseThrow();
        ItemStack ironPick = bot.getInventory().main.get(ironSlot);
        ironPick.setDamage(ironPick.getMaxDamage() - 2);
        MiningServiceTask task = rareBoundaryTask(bot, 64, 63);
        task.start(bot);
        tickToTerminal(task, bot, 40);

        require(context, task.state() == TaskState.COMPLETED,
                "one usable target break did not satisfy boundary63: " + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1
                        && InventoryAction.countItem(bot, Items.IRON_INGOT) == 6,
                "boundary63 unnecessarily replaced its exactly-usable target pick");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void serviceReturnsToExactSavedWorkFace(TestContext context) {
        Fixture fixture = spawn(context, "MiningServiceExactFaceGT", false);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        BlockPos face = bot.getBlockPos().toImmutable();
        Map<String, String> checkpoint = validCheckpoint(bot, "0", "0");
        bot.teleport(bot.getServerWorld(), face.getX() + 1.5D, face.getY(), face.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        MiningServiceTask task = new MiningServiceTask(Set.of(Blocks.DIAMOND_ORE), checkpoint);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("exact-face return failed: " + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getBlockPos().equals(face),
                    "service completed near, but not at, its saved face: " + bot.getBlockPos());
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void rareServiceSchema6PinsMissionTargetAndBoundary(TestContext context) {
        Fixture fixture = spawn(context, "RareIdentityGT", false);
        AIPlayerEntity bot = fixture.bot();
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 55);
        giveRareBoundaryKit(bot, policy, policy.torchMinCount(),
                policy.foodMinUnits(), rarePreRepairSticks(policy, false), false);
        MiningCursor cursor = miningCursor(bot.getBlockPos().toImmutable(), 0, 7);
        MiningServiceTask original = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                policy,
                55, "rare-mission", 64, cursor);
        original.start(bot);
        Map<String, String> checkpoint = original.checkpoint();
        MiningServiceTask.RestoreMetadata metadata =
                MiningServiceTask.inspectCheckpoint(checkpoint).orElseThrow();

        require(context, "8".equals(checkpoint.get("schema"))
                        && "RARE_ORE_BATCH".equals(checkpoint.get("service_profile"))
                        && "rare-mission".equals(checkpoint.get("service_mission_id"))
                        && "64".equals(checkpoint.get("service_target_count"))
                        && "55".equals(checkpoint.get("service_boundary"))
                        && String.valueOf(policy.torchMinCount())
                        .equals(checkpoint.get("torch_min_count"))
                        && String.valueOf(policy.foodMinUnits())
                        .equals(checkpoint.get("food_min_units"))
                        && String.valueOf(policy.futureStickReserve())
                        .equals(checkpoint.get("future_stick_reserve"))
                        && String.valueOf(cursor.schema()).equals(checkpoint.get("cursor_schema"))
                        && checkpoint.get("cursor_face").equals(checkpoint.get("work_face"))
                        && metadata.policy().equals(
                        policy),
                "rare schema-8 checkpoint lost exact identity: " + checkpoint);

        MiningServiceTask wrongMission = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint,
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 55),
                55, "forged-mission", 64, cursor);
        wrongMission.start(bot);
        MiningServiceTask wrongTarget = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint,
                MiningServiceTask.ServicePolicy.rareOreBatch(72, 55),
                55, "rare-mission", 72, cursor);
        wrongTarget.start(bot);
        MiningServiceTask wrongOres = new MiningServiceTask(
                Set.of(Blocks.EMERALD_ORE), checkpoint,
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 55),
                55, "rare-mission", 64, cursor);
        wrongOres.start(bot);
        require(context, wrongMission.state() == TaskState.FAILED
                        && wrongTarget.state() == TaskState.FAILED
                        && wrongOres.state() == TaskState.FAILED,
                "rare checkpoint restored under mismatched mission/target/ores identity");
        Map<String, String> cursorlessSchema6 = new LinkedHashMap<>(checkpoint);
        cursorlessSchema6.keySet().removeIf(key -> key.startsWith("cursor_"));
        Map<String, String> cursorless = Map.copyOf(cursorlessSchema6);
        MiningServiceTask cursorlessRestore = new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), cursorless,
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 55),
                55, "rare-mission", 64);
        cursorlessRestore.start(bot);
        require(context, MiningServiceTask.inspectCheckpoint(cursorless).isEmpty()
                        && cursorlessRestore.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint".equals(
                        cursorlessRestore.failureReason()),
                "schema-8 rare checkpoint retained authority without its embedded cursor");
        Map<String, String> schema5 = new LinkedHashMap<>(checkpoint);
        schema5.put("schema", "5");
        schema5.keySet().removeIf(key -> key.startsWith("cursor_"));
        require(context, MiningServiceTask.inspectCheckpoint(Map.copyOf(schema5)).isEmpty(),
                "schema-5 rare checkpoint migrated across the retry/cursor authority boundary");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void rareDescentKitRestoresSchema7OpenAlcoveCheckpoint(TestContext context) {
        Fixture fixture = spawn(context, "RareDescentKitOpenRestoreGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-open-restore";
        MiningCursor cursor = miningCursor(bot.getBlockPos().toImmutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);

        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> open = tickUntilServicePhase(
                original, bot, "OPEN_MISSION_DEPOT_ALCOVE", 20);
        require(context, "9".equals(open.get("schema"))
                        && "false".equals(open.get("mission_depot_place_committed"))
                        && "0".equals(open.get("mission_depot_clear_index"))
                        && decodeCheckpointPos(open.get("depot"))
                        .equals(bot.getBlockPos().east()),
                "OPEN checkpoint lost its schema-9 mission-depot identity: " + open);
        require(context, MiningServiceTask.inspectCheckpoint(open).isPresent(),
                "OPEN checkpoint is not independently decodable: " + open);
        original.abort(bot);

        MiningServiceTask restored = rareDescentKitTask(mission, cursor, open);
        runServiceToTerminal(restored, bot);
        BlockPos depot = decodeCheckpointPos(restored.checkpoint().get("depot"));
        require(context, bot.getServerWorld().getBlockState(depot).isOf(Blocks.CHEST)
                        && MiningServiceTask.ownedMissionDepot(bot, mission),
                "OPEN restore did not place and own the exact mission chest");
        require(context, InventoryAction.countItem(bot, Items.CHEST) == 0,
                "OPEN restore failed to consume exactly one carried chest");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void rareDescentKitWorldChestBeforeCommitRestoresWithoutDoubleSpend(
            TestContext context) {
        Fixture fixture = spawn(context, "RareDescentKitPlaceRestoreGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-place-restore";
        MiningCursor cursor = miningCursor(bot.getBlockPos().toImmutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);

        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> beforePlace = tickUntilServicePhase(
                original, bot, "PLACE_MISSION_DEPOT", 20);
        BlockPos depot = decodeCheckpointPos(beforePlace.get("depot"));
        require(context, "false".equals(
                        beforePlace.get("mission_depot_place_committed"))
                        && InventoryAction.countItem(bot, Items.CHEST) == 1
                        && bot.getServerWorld().getBlockState(depot).isAir(),
                "PLACE fixture was not immediately before the physical placement: "
                        + beforePlace);
        original.abort(bot);

        // Simulate a process death after vanilla accepted the placement but before the task wrote
        // its committed bit or mission memory. The restored PLACE phase must observe the world
        // chest and advance; it may not demand or consume a second chest.
        require(context, InventoryAction.removeItems(bot, Items.CHEST, 1),
                "fixture could not spend the physically placed chest");
        bot.getServerWorld().setBlockState(
                depot, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        MiningServiceTask afterWorldMutation = rareDescentKitTask(
                mission, cursor, beforePlace);
        afterWorldMutation.start(bot);
        Map<String, String> verify = tickUntilServicePhase(
                afterWorldMutation, bot, "VERIFY_MISSION_DEPOT", 10);
        require(context, "true".equals(verify.get("mission_depot_place_committed"))
                        && InventoryAction.countItem(bot, Items.CHEST) == 0
                        && BotMemoryStore.INSTANCE.of(bot.getUuid())
                        .recall("mining_depot_owner").isEmpty(),
                "world-first restore duplicated the chest or prematurely forged ownership: "
                        + verify);
        afterWorldMutation.abort(bot);

        MiningServiceTask afterVerifyCrash = rareDescentKitTask(mission, cursor, verify);
        runServiceToTerminal(afterVerifyCrash, bot);
        require(context, bot.getServerWorld().getBlockState(depot).isOf(Blocks.CHEST)
                        && InventoryAction.countItem(bot, Items.CHEST) == 0
                        && MiningServiceTask.ownedMissionDepot(bot, mission),
                "VERIFY restore did not commit the one physical mission chest exactly once");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void rareDescentKitRejectsOwnerPositionDifferentFromCheckpointDepot(
            TestContext context) {
        Fixture fixture = spawn(context, "RareDescentKitOwnerMismatchGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-owner-mismatch";
        MiningCursor cursor = miningCursor(bot.getBlockPos().toImmutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);

        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> beforePlace = tickUntilServicePhase(
                original, bot, "PLACE_MISSION_DEPOT", 20);
        BlockPos checkpointDepot = decodeCheckpointPos(beforePlace.get("depot"));
        original.abort(bot);
        require(context, InventoryAction.removeItems(bot, Items.CHEST, 1),
                "owner-mismatch fixture could not spend the checkpoint chest");
        bot.getServerWorld().setBlockState(
                checkpointDepot, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        MiningServiceTask placed = rareDescentKitTask(mission, cursor, beforePlace);
        placed.start(bot);
        Map<String, String> verify = tickUntilServicePhase(
                placed, bot, "VERIFY_MISSION_DEPOT", 10);
        placed.abort(bot);

        BlockPos rememberedDepot = bot.getBlockPos().west();
        bot.getServerWorld().setBlockState(
                rememberedDepot, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        memory.markPlace("mining_depot", bot.getServerWorld(), rememberedDepot);
        memory.remember("mining_depot_owner", mission);
        MiningServiceTask restored = rareDescentKitTask(mission, cursor, verify);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.FAILED
                        && "mining_service_mission_depot_owner_mismatch"
                        .equals(restored.failureReason()),
                "checkpoint depot B silently replaced the same-owner memory position A: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, memory.placeIn(bot.getServerWorld(), "mining_depot")
                        .filter(rememberedDepot::equals).isPresent(),
                "failed owner-position check rewrote durable mission-depot memory");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void rareDescentKitRejectsForgedSchemaAndIncompleteDoneCheckpoints(
            TestContext context) {
        Fixture fixture = spawn(context, "RareDescentKitSchemaRejectGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-schema-reject";
        MiningCursor cursor = miningCursor(bot.getBlockPos().toImmutable(), 0, 0);
        giveReadyRareDescentKit(bot, true);
        MiningServiceTask original = rareDescentKitTask(mission, cursor, Map.of());
        original.start(bot);
        Map<String, String> schema7 = tickUntilServicePhase(
                original, bot, "OPEN_MISSION_DEPOT_ALCOVE", 20);
        original.abort(bot);

        Map<String, String> schema6Kit = new LinkedHashMap<>(schema7);
        schema6Kit.put("schema", "6");
        assertRejectedRareDescentCheckpoint(
                context, bot, mission, cursor, Map.copyOf(schema6Kit), "schema6_kit");

        Map<String, String> cursorless = new LinkedHashMap<>(schema7);
        cursorless.keySet().removeIf(key -> key.startsWith("cursor_"));
        assertRejectedRareDescentCheckpoint(
                context, bot, mission, cursor, Map.copyOf(cursorless), "schema7_cursorless");

        Map<String, String> incompleteDone = new LinkedHashMap<>(schema7);
        incompleteDone.put("phase", "DONE");
        incompleteDone.put("budget_used", "0");
        incompleteDone.put("last_progress_budget", "0");
        incompleteDone.put("mission_depot_clear_index", "2");
        incompleteDone.put("mission_depot_place_committed", "true");
        incompleteDone.put("mission_depot_retirement_completed", "false");
        assertRejectedRareDescentCheckpoint(
                context, bot, mission, cursor, Map.copyOf(incompleteDone),
                "done_without_retirement");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "rareDescentKitPressureLive", tickLimit = 700)
    public void rareDescentKitFullInventoryRetiresOnlyCheapPicksThenMinesDiamond(
            TestContext context) {
        Fixture fixture = spawn(context, "RareDescentKitPressureGT", false);
        AIPlayerEntity bot = fixture.bot();
        String mission = "rare-descent-pressure";
        BlockPos face = bot.getBlockPos().toImmutable();
        MiningCursor cursor = miningCursor(face, 0, 0);
        giveFullRareDescentPressureInventory(bot);
        require(context, freeMainSlots(bot) == 0,
                "pressure fixture did not fill all 36 main slots: free="
                        + freeMainSlots(bot));

        MiningServiceTask service = rareDescentKitTask(mission, cursor, Map.of());
        // Drive the service through the real runtime owner. A directly ticked task is invisible to
        // DangerWatcher, which can then classify the bot as idle and spend this fixture's exact
        // 640-torch reserve on a concurrent LightAreaTask.
        TaskManager.INSTANCE.assign(bot, service,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_rare_descent_pressure"));
        BlockPos ore = face.north(2);
        BlockPos[] depotPos = {null};
        OreDigTask[] dig = {null};
        context.runAtEveryTick(() -> {
            if (service.state() == TaskState.FAILED
                    || service.state() == TaskState.CANCELLED) {
                context.throwGameTestException("descent-kit service ended as "
                        + service.state() + ":" + service.failureReason());
            }
            if (service.state() != TaskState.COMPLETED) {
                return;
            }
            if (dig[0] == null) {
                Map<String, String> terminal = service.checkpoint();
                depotPos[0] = decodeCheckpointPos(terminal.get("depot"));
                Inventory depot = ContainerAction.resolve(bot, depotPos[0]).orElseThrow();

                require(context, "9".equals(terminal.get("schema"))
                                && "DONE".equals(terminal.get("phase"))
                                && "true".equals(terminal.get(
                                "mission_depot_retirement_completed")),
                        "pressure service did not durably commit the schema-9 kit: "
                                + terminal);
                require(context, inventoryCount(depot, Items.WOODEN_PICKAXE) == 4
                                && inventoryCount(depot, Items.STONE_PICKAXE) == 5,
                        "mission chest did not preserve every retired old wood/stone pick");
                require(context, inventoryCount(depot, Items.LEATHER) == 7
                                && inventoryCount(depot, Items.FEATHER) == 9,
                        "mission chest did not preserve the carried by-product ledger");
                require(context, InventoryAction.countItem(bot, Items.WOODEN_PICKAXE) == 0
                                && InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 5
                                && bot.getInventory().main.stream()
                                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                                .allMatch(stack -> stack.getDamage() == 0),
                        "retirement left old cheap picks on the player or removed a fresh replacement");
                require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 3
                                && InventoryAction.countItem(bot, Items.DIAMOND_PICKAXE) == 1
                                && inventoryCount(depot, Items.IRON_PICKAXE) == 0
                                && inventoryCount(depot, Items.DIAMOND_PICKAXE) == 0,
                        "mission retirement moved an iron/diamond pick out of player custody");
                require(context, usableMainDurability(bot, Items.STONE_PICKAXE) >= 650
                                && targetGradeUsableMainDurability(bot) >= 8
                                && InventoryAction.countItem(bot, Items.TORCH)
                                >= MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                                && MiningFoodReserve.units(bot.getInventory())
                                >= MiningBudget.RARE_BOOTSTRAP_FOOD
                                && InventoryAction.countItem(bot, Items.COBBLESTONE)
                                + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                                + InventoryAction.countItem(bot, Items.BLACKSTONE)
                                >= MiningBudget.RARE_BOOTSTRAP_STONE_LIKE
                                && InventoryAction.countItem(bot, Items.STICK)
                                >= MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS
                                && freeMainSlots(bot) >= 4
                                && MiningServiceTask.rareDescentKitReady(bot)
                                && MiningServiceTask.ownedMissionDepot(bot, mission),
                        "pressure service missed the 650/8/640/72/60/228/free4 descent contract");
                require(context, bot.getBlockPos().equals(face),
                        "descent kit did not complete at its exact mining workface");

                bot.getServerWorld().setBlockState(
                        ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
                dig[0] = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
                // Abort a background task that may have been admitted in the END_SERVER_TICK where
                // service became terminal, before it gets a tick to mutate the proven kit.
                TaskManager.INSTANCE.abort(bot);
                TaskManager.INSTANCE.assign(bot, dig[0],
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_post_descent_kit_diamond"));
            }
            if (dig[0].state() == TaskState.FAILED
                    || dig[0].state() == TaskState.CANCELLED) {
                context.throwGameTestException("post-kit diamond dig ended as "
                        + dig[0].state() + ":" + dig[0].failureReason());
            }
            if (dig[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getServerWorld().getBlockState(ore).isAir()
                            && InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                    "post-kit OreDig did not physically break and collect one visible diamond");
            require(context, bot.getServerWorld().getBlockState(depotPos[0])
                            .isOf(Blocks.CHEST)
                            && ContainerAction.resolve(bot, depotPos[0]).isPresent()
                            && MiningServiceTask.ownedMissionDepot(bot, mission),
                    "post-kit OreDig destroyed or forgot the mission chest");
            require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                    "post-kit OreDig killed the bot");
            cleanup(context, fixture);
        });
    }

    private static MiningServiceTask rareDescentKitTask(
            String mission,
            MiningCursor cursor,
            Map<String, String> checkpoint) {
        return new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), checkpoint,
                MiningServiceTask.ServicePolicy.rareDescentKit(64),
                0, mission, 64, cursor);
    }

    private static Map<String, String> tickUntilServicePhase(
            MiningServiceTask task,
            AIPlayerEntity bot,
            String expectedPhase,
            int maxTicks) {
        for (int tick = 0; tick < maxTicks && task.state() == TaskState.RUNNING; tick++) {
            Map<String, String> checkpoint = task.checkpoint();
            if (expectedPhase.equals(checkpoint.get("phase"))) {
                return checkpoint;
            }
            task.tick(bot);
        }
        Map<String, String> terminal = task.checkpoint();
        if (expectedPhase.equals(terminal.get("phase"))) {
            return terminal;
        }
        throw new IllegalStateException("service never reached " + expectedPhase
                + ":state=" + task.state() + ":reason=" + task.failureReason()
                + ":checkpoint=" + terminal);
    }

    private static void assertRejectedRareDescentCheckpoint(
            TestContext context,
            AIPlayerEntity bot,
            String mission,
            MiningCursor cursor,
            Map<String, String> checkpoint,
            String label) {
        require(context, MiningServiceTask.inspectCheckpoint(checkpoint).isEmpty(),
                label + " checkpoint retained standalone restore authority: " + checkpoint);
        MiningServiceTask rejected = rareDescentKitTask(mission, cursor, checkpoint);
        rejected.start(bot);
        require(context, rejected.state() == TaskState.FAILED
                        && "mining_service_invalid_checkpoint"
                        .equals(rejected.failureReason()),
                label + " checkpoint did not fail closed: " + rejected.state()
                        + ":" + rejected.failureReason());
    }

    private static void giveReadyRareDescentKit(AIPlayerEntity bot, boolean chest) {
        if (chest) {
            InventoryAction.giveItem(bot, new ItemStack(Items.CHEST));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        for (int index = 0; index < 5; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        giveStackedItem(bot, Items.TORCH,
                MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
        giveStackedItem(bot, Items.BREAD, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveStackedItem(bot, Items.COBBLESTONE,
                MiningBudget.RARE_BOOTSTRAP_STONE_LIKE);
        giveStackedItem(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
    }

    private static void giveFullRareDescentPressureInventory(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.CHEST));
        for (int index = 0; index < 4; index++) {
            giveExhaustedPick(bot, Items.WOODEN_PICKAXE);
        }
        for (int index = 0; index < 5; index++) {
            giveExhaustedPick(bot, Items.STONE_PICKAXE);
        }
        for (int index = 0; index < 3; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 6));
        giveStackedItem(bot, Items.TORCH,
                MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
        giveStackedItem(bot, Items.BREAD, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveStackedItem(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS + 5 * 2);
        giveStackedItem(bot, Items.COBBLESTONE,
                MiningBudget.RARE_BOOTSTRAP_STONE_LIKE + 5 * 3 + 2);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.LEATHER, 7));
        InventoryAction.giveItem(bot, new ItemStack(Items.FEATHER, 9));
    }

    private static int inventoryCount(Inventory inventory, net.minecraft.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int usableMainDurability(
            AIPlayerEntity bot, net.minecraft.item.Item item) {
        return bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(item))
                .mapToInt(MiningServiceTask::usableDurability)
                .sum();
    }

    private static int targetGradeUsableMainDurability(AIPlayerEntity bot) {
        return usableMainDurability(bot, Items.IRON_PICKAXE)
                + usableMainDurability(bot, Items.DIAMOND_PICKAXE);
    }

    private static BlockPos decodeCheckpointPos(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing_checkpoint_pos");
        }
        String[] parts = encoded.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid_checkpoint_pos:" + encoded);
        }
        return new BlockPos(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static MiningServiceTask rareBoundaryTask(
            AIPlayerEntity bot, int target, int boundary) {
        BlockPos face = bot.getBlockPos().toImmutable();
        return new MiningServiceTask(
                Set.of(Blocks.DIAMOND_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.rareOreBatch(target, boundary),
                boundary, "rare-horizon-" + target, target,
                miningCursor(face, 0, boundary / 8));
    }

    private static void giveRareBoundaryKit(AIPlayerEntity bot,
                                            MiningServiceTask.ServicePolicy policy,
                                            int torches,
                                            int food,
                                            int sticks,
                                            boolean freshChannelPicks) {
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        int channelStone = freshChannelPicks ? 0
                : MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                * MiningBudget.STONE_PICKAXE_HEAD_COST;
        giveStackedItem(bot, Items.COBBLESTONE,
                policy.emergencyBlocksReserved() + channelStone);
        if (freshChannelPicks) {
            for (int index = 0;
                 index < MiningBudget.RARE_TUNNELING_SERVICE_TARGET; index++) {
                InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
            }
        }
        giveStackedItem(bot, Items.TORCH, torches);
        giveStackedItem(bot, Items.BREAD, food);
        giveStackedItem(bot, Items.STICK, sticks);
    }

    private static int rareProtectedSticks(MiningServiceTask.ServicePolicy policy) {
        return policy.futureStickReserve() + MiningBudget.DIAMOND_STACK_TARGET_TOOL_STICKS;
    }

    private static int rarePreRepairSticks(
            MiningServiceTask.ServicePolicy policy, boolean freshChannelPicks) {
        int currentEpoch = freshChannelPicks ? 0
                : MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                * MiningBudget.STONE_PICKAXE_STICK_COST;
        return rareProtectedSticks(policy) + currentEpoch;
    }

    private static int putStackedInventory(
            Inventory inventory, int startSlot, net.minecraft.item.Item item, int count) {
        int slot = startSlot;
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            if (slot >= inventory.size()) {
                throw new IllegalArgumentException("inventory_fixture_capacity_depleted");
            }
            int batch = Math.min(item.getMaxCount(), remaining);
            inventory.setStack(slot++, new ItemStack(item, batch));
            remaining -= batch;
        }
        return slot;
    }

    private static MiningCursor miningCursor(BlockPos face, int direction, int batches) {
        return new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                face,
                face,
                direction,
                0,
                24,
                48,
                batches);
    }

    private static void prepareDisposalPocket(Fixture fixture, Direction direction) {
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = bot.getBlockPos();
        var world = bot.getServerWorld();
        BlockPos entry = face.offset(direction);
        BlockPos sink = face.offset(direction, 2);
        BlockPos back = face.offset(direction, 3);
        world.setBlockState(entry, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(entry.up(), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink.up(), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(back, Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(back.up(), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        Direction left = direction.rotateYCounterclockwise();
        Direction right = direction.rotateYClockwise();
        for (BlockPos cell : new BlockPos[]{entry, sink}) {
            world.setBlockState(cell.offset(left),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.offset(left).up(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.offset(right),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.offset(right).up(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    private static void buildBedrockCage(AIPlayerEntity bot, BlockPos center) {
        var world = bot.getServerWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(center.add(dx, -1, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(center.add(dx, 2, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                if (dx != 0 || dz != 0) {
                    world.setBlockState(center.add(dx, 0, dz),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                    world.setBlockState(center.add(dx, 1, dz),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(center, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(center.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static Box sinkBox(BlockPos sink) {
        return new Box(sink.getX(), sink.getY(), sink.getZ(),
                sink.getX() + 1.0D, sink.getY() + 2.0D, sink.getZ() + 1.0D);
    }

    private static int sinkCount(AIPlayerEntity bot, BlockPos sink, net.minecraft.item.Item item) {
        Box raw = sinkBox(sink);
        return bot.getServerWorld().getEntitiesByClass(
                        ItemEntity.class, raw.expand(0.01D),
                        entity -> entity.isAlive()
                                && fullyContains(raw, entity.getBoundingBox())).stream()
                .filter(entity -> entity.getStack().isOf(item))
                .mapToInt(entity -> entity.getStack().getCount())
                .sum();
    }

    private static boolean fullyContains(Box outer, Box inner) {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX
                && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }

    private static boolean isSolid(AIPlayerEntity bot, BlockPos pos) {
        var state = bot.getServerWorld().getBlockState(pos);
        return !state.isReplaceable()
                && !state.getCollisionShape(bot.getServerWorld(), pos).isEmpty();
    }

    private static void giveStackedItem(AIPlayerEntity bot, net.minecraft.item.Item item, int count) {
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            int batch = Math.min(item.getMaxCount(), remaining);
            InventoryAction.giveItem(bot, new ItemStack(item, batch));
            remaining -= batch;
        }
    }

    private static void tickToTerminal(MiningServiceTask task, AIPlayerEntity bot, int maxTicks) {
        task.start(bot);
        for (int tick = 0; tick < maxTicks && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
    }

    private static Fixture spawn(TestContext context, String name, boolean withDepot) {
        var world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(feet.add(dx, -1, dz),
                        Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.add(dx, 0, dz),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.add(dx, 1, dz),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos depot = feet.east(2);
        if (withDepot) {
            world.setBlockState(depot, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        BotMemoryStore.INSTANCE.remove(bot.getUuid());
        if (withDepot) {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace("depot", world, depot);
        }
        return new Fixture(name, bot, depot);
    }

    private static Map<String, String> validCheckpoint(
            AIPlayerEntity bot, String budget, String lastProgress) {
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "8");
        values.put("work_face", bot.getBlockPos().getX() + ","
                + bot.getBlockPos().getY() + "," + bot.getBlockPos().getZ());
        values.put("phase", "PREPARE");
        values.put("channel_tools", "false");
        values.put("service_profile", policy.profile().name());
        values.put("service_mission_id", "standalone");
        values.put("service_dimension", bot.getServerWorld().getRegistryKey()
                .getValue().toString());
        values.put("service_target_count", "0");
        values.put("service_boundary", "0");
        values.put("target_tool_usable", String.valueOf(
                policy.targetToolUsableDurability()));
        values.put("channel_tool_usable", String.valueOf(
                policy.channelToolUsableDurability()));
        values.put("food_min_units", String.valueOf(policy.foodMinUnits()));
        values.put("torch_min_count", String.valueOf(policy.torchMinCount()));
        values.put("free_slots_min", String.valueOf(policy.freeSlotsMin()));
        values.put("emergency_blocks_reserved", String.valueOf(
                policy.emergencyBlocksReserved()));
        values.put("future_stick_reserve", String.valueOf(
                policy.futureStickReserve()));
        values.put("crafting_table_required", String.valueOf(
                policy.craftingTableRequired()));
        values.put("ores", OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
        values.put("budget_used", budget);
        values.put("last_progress_budget", lastProgress);
        return Map.copyOf(values);
    }

    private static void giveObsidianServiceKit(AIPlayerEntity bot,
                                               int diamondRawDurability,
                                               int stonePicks,
                                               int cobblestone) {
        // Keep directly ticked service tests isolated from held-tool background resupply.
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamage(diamond.getMaxDamage() - diamondRawDurability);
        InventoryAction.giveItem(bot, diamond);
        for (int i = 0; i < stonePicks; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        if (cobblestone > 0) {
            InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, cobblestone));
        }
    }

    private static int diamondRawDurability(AIPlayerEntity bot) {
        return bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.DIAMOND_PICKAXE))
                .mapToInt(stack -> stack.getMaxDamage() - stack.getDamage())
                .sum();
    }

    private static void runServiceToTerminal(MiningServiceTask task, AIPlayerEntity bot) {
        task.start(bot);
        for (int tick = 0; tick < 400 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }
        if (task.state() != TaskState.COMPLETED) {
            throw new IllegalStateException("service horizon stage ended as " + task.state()
                    + ":" + task.failureReason());
        }
    }

    private static void giveExhaustedStonePicks(AIPlayerEntity bot, int count) {
        for (int index = 0; index < count; index++) {
            ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
            pick.setDamage(pick.getMaxDamage() - 1);
            InventoryAction.giveItem(bot, pick);
        }
    }

    private static void giveExhaustedPick(AIPlayerEntity bot, net.minecraft.item.Item item) {
        ItemStack pick = new ItemStack(item);
        pick.setDamage(pick.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, pick);
    }

    private static long unusableCheapPickaxes(AIPlayerEntity bot) {
        return bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.WOODEN_PICKAXE)
                        || stack.isOf(Items.STONE_PICKAXE)
                        || stack.isOf(Items.IRON_PICKAXE))
                .filter(stack -> MiningServiceTask.usableDurability(stack) == 0)
                .count();
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        return (int) bot.getInventory().main.stream().filter(ItemStack::isEmpty).count();
    }

    private static int firstEmptyMainSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            if (bot.getInventory().main.get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static void exhaustAllStonePicks(AIPlayerEntity bot) {
        bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .forEach(stack -> stack.setDamage(stack.getMaxDamage() - 1));
    }

    private static void assertServiceResources(TestContext context,
                                               AIPlayerEntity bot,
                                               int stoneLike,
                                               int sticks,
                                               String stage) {
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                        + InventoryAction.countItem(bot, Items.BLACKSTONE) == stoneLike,
                stage + " did not retain the exact future stone reserve");
        require(context, InventoryAction.countItem(bot, Items.STICK) == sticks,
                stage + " did not retain the exact future stick reserve");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                stage + " lost the carried crafting table");
        int channelDurability = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(MiningServiceTask::usableDurability)
                .sum();
        require(context, channelDurability >= 520,
                stage + " did not restore four fresh channel picks");
    }

    private static void cleanup(TestContext context, Fixture fixture) {
        BotMemoryStore.INSTANCE.remove(fixture.bot().getUuid());
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos depot) {
    }
}
