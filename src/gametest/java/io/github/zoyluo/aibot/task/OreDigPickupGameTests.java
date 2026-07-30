package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Live strict-survival regression coverage for OreDig's physical target-drop ledger. */
public final class OreDigPickupGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupDropCatchStrict", tickLimit = 500)
    public void adjacentCoalOverFiveDeepShaftIsCaughtBeforeDeepFall(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDropCatchGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.north();
        BlockPos support = ore.down();
        var world = bot.getServerWorld();

        world.setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (int depth = 1; depth <= 5; depth++) {
            world.setBlockState(ore.down(depth), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(ore.down(6), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));
        require(context, !ObservableWorldQuery.canObserveCell(bot, support),
                "fixture did not preserve the ore-occluded support-center ray");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_drop_catch"));
        AtomicBoolean sawCatchSupport = new AtomicBoolean();
        AtomicBoolean sawPhysicalBreak = new AtomicBoolean();
        AtomicInteger settlementCallbacks = new AtomicInteger();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (world.getBlockState(ore).isAir()) {
                sawPhysicalBreak.set(true);
                if (world.getBlockState(support).isOf(Blocks.DIRT)) {
                    sawCatchSupport.set(true);
                } else {
                    require(context, settlementCallbacks.incrementAndGet() <= 1,
                            "coal break remained open past its first task-settlement tick");
                }
            }
            for (ItemEntity entity : world.getEntitiesByClass(
                    ItemEntity.class, new Box(ore.down(5)).expand(1.0D, 5.0D, 1.0D),
                    item -> item.getStack().isOf(Items.COAL))) {
                require(context, entity.getBlockY() >= ore.getY(),
                        "coal entity fell below its supported break cell: "
                                + entity.getBlockPos().toShortString());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawCatchSupport.get() && sawPhysicalBreak.get(),
                    "drop-catch fixture did not settle the break with physical support");
            require(context, world.getBlockState(support).isOf(Blocks.DIRT),
                    "drop catch did not spend the first sacrificial block");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "supported coal was not physically recovered");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) > pickupBaseline,
                    "supported coal bypassed vanilla pickup statistics");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupDiagonalBreakStrict", tickLimit = 500)
    public void diagonalEyeHeightOreWaitsForCardinalWorkPose(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDiagonalBreakGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.east().north().up();
        var world = bot.getServerWorld();
        world.setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "diagonal eye-height coal is not strictly observable");
        require(context, Math.abs(ore.getX() - start.getX())
                        + Math.abs(ore.getZ() - start.getZ()) == 2,
                "fixture is not the diagonal break geometry");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_diagonal_break_work_pose"));
        AtomicBoolean sawCardinalWorkPose = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (encode(ore).equals(task.checkpoint().get("active_break_pos"))) {
                BlockPos feet = bot.getBlockPos();
                int horizontalManhattan = Math.abs(ore.getX() - feet.getX())
                        + Math.abs(ore.getZ() - feet.getZ());
                require(context, horizontalManhattan <= 1,
                        "diagonal ore opened before a cardinal work pose: bot="
                                + feet.toShortString() + " ore=" + ore.toShortString());
                require(context, !feet.equals(start),
                        "diagonal ore opened from the original corner pose");
                sawCardinalWorkPose.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawCardinalWorkPose.get(),
                    "fixture never exercised the cardinal work-pose boundary");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "diagonal coal was not physically recovered");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) > pickupBaseline,
                    "diagonal coal bypassed vanilla pickup statistics");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupHighWorkPoseStrict", tickLimit = 700)
    public void threeAboveOreRequiresReachableHighWorkPoseForNaturalPickup(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupHighFaceGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos workPose = start.east().south().up(2);
        BlockPos firstStep = workPose.south().down();
        BlockPos ore = start.east().up(3);

        // A high ore can be inside vanilla eye reach while its launched ItemEntity can still drift
        // onto an unreachable ledge. Build a real two-step staircase to an observed side work pose;
        // the ore may open only after ordinary movement reaches that recoverable envelope.
        world.setBlockState(firstStep.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(firstStep, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(firstStep.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, !firstStep.up(2).equals(ore),
                "high-face staircase cannot put its second jump head inside the ore");
        require(context, ore.getY() - start.getY() == 3
                        && Math.abs(ore.getX() - start.getX()) == 1,
                "fixture does not reproduce the dx=1,dy=+3 break boundary");
        require(context, bot.getEyePos().squaredDistanceTo(ore.toCenterPos()) <= 20.25D,
                "high-face fixture must begin inside the old vanilla-reach shortcut");
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "high-face iron must be strictly observable from the lower floor");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON));
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_high_face"));
        AtomicBoolean sawHighWorkPose = new AtomicBoolean();
        AtomicBoolean sawPhysicalBreak = new AtomicBoolean();
        AtomicBoolean sawNaturalDrop = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            if (encode(ore).equals(live.get("active_break_pos"))) {
                require(context, bot.getBlockPos().equals(workPose),
                        "dy=+3 ore opened before reaching its high side work pose: bot="
                                + bot.getBlockPos().toShortString()
                                + " expected=" + workPose.toShortString());
                Standability.clearCache();
                require(context, Standability.isStandable(world, workPose),
                        "dy=+3 ore opened from a non-standable high work pose");
                sawHighWorkPose.set(true);
            }
            if (world.getBlockState(ore).isAir()) {
                require(context, sawHighWorkPose.get(),
                        "dy=+3 ore broke before its high work pose was observed");
                sawPhysicalBreak.set(true);
                if (!world.getEntitiesByClass(
                        ItemEntity.class, new Box(ore).expand(3.0D),
                        entity -> entity.getStack().isOf(Items.RAW_IRON)).isEmpty()) {
                    sawNaturalDrop.set(true);
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawHighWorkPose.get() && sawPhysicalBreak.get(),
                    "fixture did not exercise high-face work-pose mining");
            require(context, sawNaturalDrop.get(),
                    "high-face iron entered inventory without an observed vanilla ItemEntity");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "high-face iron drop was not physically recovered");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON)) > pickupBaseline,
                    "high-face raw iron bypassed vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos")
                            && !task.checkpoint().containsKey("pending_pickup_last_seen_pos"),
                    "completed high-face mining retained pickup debt: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupTwoAboveCardinalStrict", tickLimit = 700)
    public void twoAboveCardinalOreUsesDropShaftWorkPoseForNaturalPickup(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupTwoAboveGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos ore = start.north().up(2);
        BlockPos oreSupport = ore.down();
        BlockPos workPose = ore.down(2);

        // Freeze the strict seed-3000 boundary: the ore is cardinal, dy=+2 and inside vanilla
        // reach from the lower floor. The solid support prevents an accidental immediate drop;
        // the task must first open the body column, enter the exact block below the ore and let the
        // vanilla ItemEntity fall through that controlled shaft without item manipulation.
        world.setBlockState(oreSupport, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, ore.getY() - start.getY() == 2
                        && Math.abs(ore.getX() - start.getX())
                        + Math.abs(ore.getZ() - start.getZ()) == 1,
                "fixture does not reproduce the dy=+2 cardinal break boundary");
        require(context, bot.getEyePos().squaredDistanceTo(ore.toCenterPos()) <= 20.25D,
                "two-above cardinal ore must begin inside vanilla reach");
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "two-above cardinal ore is not strictly observable");
        require(context, world.getBlockState(workPose).isAir()
                        && world.getBlockState(oreSupport).isOf(Blocks.STONE)
                        && !world.getBlockState(workPose.down())
                        .getCollisionShape(world, workPose.down()).isEmpty(),
                "fixture does not require a supported head cell to be opened before entry");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON));
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_pickup_two_above_cardinal"));
        AtomicBoolean reachedDropShaftWorkPose = new AtomicBoolean();
        AtomicBoolean sawNaturalDrop = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (bot.getBlockPos().equals(workPose)) {
                reachedDropShaftWorkPose.set(true);
            }
            Map<String, String> live = task.checkpoint();
            if (encode(ore).equals(live.get("active_break_pos"))) {
                require(context, bot.getBlockPos().equals(workPose),
                        "dy=+2 cardinal ore opened outside its drop-shaft work pose: bot="
                                + bot.getBlockPos().toShortString()
                                + " expected=" + workPose.toShortString());
                Standability.clearCache();
                require(context, Standability.isStandable(world, workPose),
                        "ore opened before its controlled drop shaft became standable");
            }
            if (world.getBlockState(ore).isAir()) {
                require(context, reachedDropShaftWorkPose.get(),
                        "dy=+2 cardinal ore broke before the drop-shaft work pose was reached");
                if (!world.getEntitiesByClass(
                        ItemEntity.class, new Box(ore).expand(2.0D),
                        entity -> entity.getStack().isOf(Items.RAW_IRON)).isEmpty()) {
                    sawNaturalDrop.set(true);
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, reachedDropShaftWorkPose.get(),
                    "two-above mining never reached its controlled drop-shaft pose");
            require(context, sawNaturalDrop.get(),
                    "two-above iron entered inventory without an observed vanilla ItemEntity");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "two-above raw iron was not physically recovered");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON)) > pickupBaseline,
                    "two-above raw iron bypassed vanilla pickup statistics");
            require(context, !live.containsKey("pending_pickup_pos")
                            && !live.containsKey("active_break_pos"),
                    "completed two-above mining retained physical debt: " + live);
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupLowerLedgeStrict", tickLimit = 400)
    public void lowerFloorOreClearsSweptPickupEgressBeforeBreaking(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupLowerLedgeGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos ore = start.north().down();
        BlockPos landingSupport = ore.down();
        BlockPos upperOverhang = ore.up(2);
        world.setBlockState(ore, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landingSupport, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(upperOverhang, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture floor ore is not exposed to strict perception");
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON));
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_lower_ledge"));
        AtomicBoolean egressCleared = new AtomicBoolean();
        AtomicBoolean oreBroken = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (world.getBlockState(upperOverhang).isAir()) {
                egressCleared.set(true);
            }
            if (world.getBlockState(ore).isAir()) {
                require(context, egressCleared.get(),
                        "finite floor ore broke before its swept pickup egress was clear");
                oreBroken.set(true);
            }
            require(context, world.getBlockState(start.down()).isOf(Blocks.STONE)
                            && world.getBlockState(landingSupport).isOf(Blocks.STONE),
                    "pickup recovery modified either factual support block");
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, egressCleared.get() && oreBroken.get(),
                    "fixture did not exercise ordered egress clearing and target break");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "expected exactly one physically recovered raw iron");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON)) > pickupBaseline,
                    "raw iron entered inventory without vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed lower-ledge pickup retained its durable debt");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupLowerShaftCatchStrict", tickLimit = 500)
    public void lowerFloorCoalOverOpenShaftGetsPhysicalDropSupport(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupLowerShaftGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos ore = start.north().down();
        BlockPos support = ore.down();
        BlockPos anchor = support.down();
        world.setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(support, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture lower coal is not exposed to strict perception");
        require(context, !ObservableWorldQuery.canObserveCell(bot, support),
                "fixture did not preserve the lower ore-occluded support ray");

        assertStrictCapabilities(context, bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        int fillerBaseline = InventoryAction.countItem(bot, Items.COBBLESTONE);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_lower_shaft"));
        AtomicBoolean sawPhysicalBreak = new AtomicBoolean();
        AtomicBoolean sawCatchSupport = new AtomicBoolean();
        AtomicInteger settlementCallbacks = new AtomicInteger();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (world.getBlockState(ore).isAir()) {
                sawPhysicalBreak.set(true);
                if (world.getBlockState(support).isOf(Blocks.COBBLESTONE)) {
                    sawCatchSupport.set(true);
                } else {
                    // BlockMiner removes the ore before OreDig observes DONE on its following
                    // task tick. Permit that single callback edge, but never a second open tick in
                    // which the fresh ItemEntity could fall below the reachable pickup envelope.
                    require(context, settlementCallbacks.incrementAndGet() <= 1,
                            "lower coal break remained unsupported past its first settlement tick");
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawPhysicalBreak.get() && sawCatchSupport.get(),
                    "lower-shaft fixture did not exercise the physical drop catch");
            require(context, world.getBlockState(support).isOf(Blocks.COBBLESTONE),
                    "lower-shaft drop support was not present at task completion");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == fillerBaseline - 1,
                    "lower-shaft drop catch did not consume exactly one real support block");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                            == MiningBudget.EMERGENCY_STONE_LIKE,
                    "lower-shaft drop catch consumed the protected emergency reserve");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "supported lower coal was not physically recovered");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) > pickupBaseline,
                    "lower coal bypassed vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed lower-shaft pickup retained its durable debt");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreDropCommitReserveStrict", tickLimit = 40)
    public void exactProtectedReserveRejectsOpenShaftOreBeforeBreakAndRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDropCommitReserveGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.north();
        var world = context.getWorld();
        int protectedStone = MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (int depth = 1; depth <= 5; depth++) {
            world.setBlockState(ore.down(depth), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(ore.down(6), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone));
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "drop-commit fixture did not expose its finite coal");
        require(context, MaterialPalette.pickPathSupportBlockSlot(
                        bot, protectedStone).isEmpty(),
                "exact parent reserve exposed a drop-support block");

        Map<String, String> initial = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, initial);
        task.start(bot);
        task.tick(bot); // acquire the observed finite ore
        task.tick(bot); // reject the break before active/pending debt can be published
        Map<String, String> blocked = task.checkpoint();

        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(ore).isOf(Blocks.COAL_ORE),
                "exact-reserve drop gate modified or ended the finite ore: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone,
                "drop commit consumed the protected parent reserve");
        require(context, !blocked.containsKey("active_break_pos")
                        && !blocked.containsKey("pending_pickup_pos")
                        && !blocked.containsKey("pending_pickup_last_seen_pos")
                        && OreDigTask.inspectCheckpoint(blocked).isPresent(),
                "rejected drop commit published physical debt or an invalid checkpoint: " + blocked);
        require(context, bot.getActionPack().isMiningIdle(),
                "rejected drop commit left a target miner active");

        task.cancel(bot, "gametest_drop_commit_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, blocked);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> after = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && world.getBlockState(ore).isOf(Blocks.COAL_ORE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && !after.containsKey("active_break_pos")
                        && !after.containsKey("pending_pickup_pos")
                        && Integer.parseInt(after.get("budget_used"))
                        > Integer.parseInt(blocked.get("budget_used")),
                "checkpoint restart bypassed the exact-reserve drop gate: before="
                        + blocked + " after=" + after);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreDropCommitRevokedStrict", tickLimit = 50)
    public void activeBreakCancelsWhenItsOnlySurplusSupportDisappearsBeforeRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDropCommitRevokedGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos ore = start.north();
        var world = context.getWorld();
        int protectedStone = MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlockState(
                ore, Blocks.DEEPSLATE_COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (int depth = 1; depth <= 5; depth++) {
            world.setBlockState(ore.down(depth), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(ore.down(6), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone + 1));

        Map<String, String> initial = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.DEEPSLATE_COAL_ORE)));
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.DEEPSLATE_COAL_ORE), 1, 0, protectedStone, initial);
        task.start(bot);
        task.tick(bot); // acquire
        task.tick(bot); // pass the support gate and begin the slow deepslate break
        Map<String, String> active = task.checkpoint();
        require(context, world.getBlockState(ore).isOf(Blocks.DEEPSLATE_COAL_ORE)
                        && encode(ore).equals(active.get("active_break_pos"))
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == protectedStone + 1,
                "fixture did not open an intact, support-authorized target break: " + active);

        require(context, InventoryAction.removeItems(bot, Items.COBBLESTONE, 1),
                "fixture could not consume the sole surplus support");
        task.tick(bot);
        Map<String, String> revoked = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(ore).isOf(Blocks.DEEPSLATE_COAL_ORE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && !revoked.containsKey("active_break_pos")
                        && !revoked.containsKey("pending_pickup_pos")
                        && bot.getActionPack().isMiningIdle(),
                "revoked support authorization continued or retained the finite break: "
                        + revoked);

        task.cancel(bot, "gametest_revoked_support_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.DEEPSLATE_COAL_ORE), 1, 0, protectedStone, revoked);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> after = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && world.getBlockState(ore).isOf(Blocks.DEEPSLATE_COAL_ORE)
                        && !after.containsKey("active_break_pos")
                        && !after.containsKey("pending_pickup_pos")
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone,
                "restart recreated an active break after its support commit was revoked: " + after);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBonusChannelStrict", tickLimit = 500)
    public void denseBonusOreCannotStarveActiveChannelBlock(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreBonusChannelGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos channelFeet = fixture.start().north();
        BlockPos channelHead = channelFeet.up();
        world.setBlockState(channelFeet, Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(channelHead, Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 1, Set.of(Blocks.NETHER_GOLD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.NETHER_GOLD_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_bonus_channel"));
        // Advance exactly one task tick before the world callback race: this opens the real
        // deepslate channel BlockMiner but cannot complete a deepslate break. Injecting the dense
        // bonus wall now deterministically freezes the ownership boundary under test.
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING
                        && !bot.getActionPack().isMiningIdle()
                        && !world.getBlockState(channelFeet).isAir()
                        && !world.getBlockState(channelHead).isAir(),
                "fixture did not establish an active channel block before bonus injection");
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                world.setBlockState(fixture.start().west(2).add(0, dy, dz),
                        Blocks.COPPER_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        int deathBaseline = deathCount(bot);

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (!world.getBlockState(channelFeet).isAir()
                    || !world.getBlockState(channelHead).isAir()) {
                require(context, countCopperWall(world, fixture.start()) == 15,
                        "bonus ore preempted the active channel BlockMiner");
            }
            int remainingCopper = countCopperWall(world, fixture.start());
            if (remainingCopper > 7) {
                return;
            }
            require(context, world.getBlockState(channelFeet).isAir()
                            && world.getBlockState(channelHead).isAir(),
                    "bonus mining started before the active channel block finished");
            require(context, remainingCopper == 7,
                    "bonus DONE accounting skipped past the exact 8-block cap: remaining="
                            + remainingCopper);
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBonusWallBandStrict", tickLimit = 500)
    public void bonusOreMinesSideWallWithoutRemovingCurrentSupport(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreBonusWallBandGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos support = start.down();
        BlockPos sideWall = start.west();

        // Reproduce the seed-3000 capacity-resume shape: a rich copper vein is visible below the
        // saved branch face while one genuinely incidental block is exposed in the side wall.
        // The wall block may be taken, but the lower block must never become bonus work.
        world.setBlockState(support, Blocks.COPPER_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sideWall, Blocks.COPPER_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north(), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north().up(), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, ObservableWorldQuery.canObserveBlock(bot, support)
                        && ObservableWorldQuery.canObserveBlock(bot, sideWall),
                "bonus wall-band fixture is not strictly observable");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("inventory_service_used", "true");
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_bonus_wall_band"));
        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        AtomicBoolean sideWallMined = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            require(context, world.getBlockState(support).isOf(Blocks.COPPER_ORE),
                    "bonus mining removed the current support block");
            require(context, bot.getBlockPos().getY() >= start.getY(),
                    "bonus mining lowered the durable branch work face");
            if (world.getBlockState(sideWall).isAir()) {
                sideWallMined.set(true);
            }
            int remaining = Integer.parseInt(task.checkpoint().get("steps_left"));
            if (!sideWallMined.get() || bot.getBlockPos().getZ() >= start.getZ()
                    || remaining >= 12) {
                return;
            }
            require(context, "0".equals(task.checkpoint().get("direction"))
                            && remaining < 12,
                    "capacity-resumed branch did not continue from its saved cursor: "
                            + task.checkpoint());
            task.cancel(bot, "gametest_complete");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreLavaRerouteStrict", tickLimit = 180)
    public void visibleLavaRotatesTheBranchInsteadOfAssigningImpossibleEvade(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreLavaRerouteGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        // Keep one source exposed to the south while fixture stone contains its other three sides.
        // The callback clears the one vanilla flow cell before every repeated watcher scan, making
        // this a stable observation instead of a fluid-tick race.
        BlockPos lava = fixture.start().north(2);
        world.setBlockState(lava.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.south().east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.south().west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // The east wall is factual new territory; the open west side is an already-controlled
        // corridor and must not be selected merely because the old square cursor rotated there.
        world.setBlockState(
                fixture.start().east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(
                fixture.start().east().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_lava_reroute"));

        DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                "visible branch lava replaced OreDig with an impossible underground Evade");
        require(context, "1".equals(task.checkpoint().get("direction"))
                        && "12".equals(task.checkpoint().get("steps_left")),
                "DangerWatcher did not atomically preserve the unfinished leg through the east "
                        + "fresh-work detour: " + task.checkpoint());
        AtomicBoolean rotatedEast = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(lava.south(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                    "repeated lava scan replaced OreDig during its bounded escape window");
            require(context, task.state() == TaskState.RUNNING,
                    "lava reroute ended OreDig as " + task.state() + ":" + task.failureReason());
            String direction = task.checkpoint().get("direction");
            if ("1".equals(direction)) {
                rotatedEast.set(true);
            }
            if (rotatedEast.get() && !"1".equals(direction)) {
                context.throwGameTestException(
                        "same visible lava repeatedly rotated the branch: direction=" + direction);
            }
            require(context, world.getBlockState(lava).isOf(Blocks.LAVA),
                    "lava reroute mutated the factual lava source");
            require(context, bot.getBlockPos().getZ() >= fixture.start().getZ(),
                    "OreDig advanced toward the rejected north lava branch");
            if (bot.getBlockPos().getX() > fixture.start().getX()) {
                require(context, rotatedEast.get(),
                        "OreDig left the lava radius without publishing the east cursor");
                DangerWatcher.INSTANCE.clear(bot);
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 140) {
                context.throwGameTestException(
                        "OreDig did not physically leave the lava-facing origin within 140 ticks");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreWatcherLavaClusterRerouteStrict", tickLimit = 20)
    public void watcherDoesNotMistakeAVisibleLavaPoolForTheActiveBranchCell(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreWatcherLavaClusterGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos lavaA = start.north(2);
        BlockPos lavaB = lavaA.east();
        world.setBlockState(lavaA, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lavaB, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));
        require(context, ObservableWorldQuery.canObserveCell(bot, lavaA)
                        && ObservableWorldQuery.canObserveCell(bot, lavaB),
                "watcher lava-cluster fixture did not expose both fluid cells");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_watcher_lava_cluster"));

        require(context, DangerWatcher.INSTANCE.scanBot(world.getServer(), bot),
                "watcher did not claim the visible lava cluster");
        Map<String, String> rerouted = task.checkpoint();
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                        && task.state() == TaskState.RUNNING
                        && "1".equals(rerouted.get("direction"))
                        && "12".equals(rerouted.get("steps_left"))
                        && world.getBlockState(lavaA).isOf(Blocks.LAVA)
                        && world.getBlockState(lavaB).isOf(Blocks.LAVA)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE + 1,
                "watcher sealed an arbitrary pool cell instead of publishing a real reroute: "
                        + rerouted);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreDirectLavaRerouteStrict", tickLimit = 20)
    public void directLavaBoundaryAndRestartRetainOreDigOwnership(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDirectLavaRerouteGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos lava = start.north(2);
        world.setBlockState(lava.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // task-first order: direct adjacency preflight reroutes before the watcher.

        require(context, task.state() == TaskState.RUNNING
                        && "1".equals(task.checkpoint().get("direction"))
                        && "12".equals(task.checkpoint().get("steps_left")),
                "direct lava boundary did not preserve its east detour: " + task.checkpoint());
        require(context, task.avoidObservedLava(bot, lava),
                "post-task watcher order handed the already-rerouted branch to generic Evade");

        Map<String, String> rerouted = new LinkedHashMap<>(task.checkpoint());
        task.cancel(bot, "gametest_restart");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, rerouted);
        restored.start(bot);
        require(context, "1".equals(restored.checkpoint().get("direction"))
                        && restored.avoidObservedLava(bot, lava),
                "checkpoint restore lost ownership of the visible side/rear lava source");
        require(context, world.getBlockState(lava).isOf(Blocks.LAVA)
                        && bot.getBlockPos().equals(start),
                "ownership proof moved the bot or mutated the factual lava source");
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreFactualCornerLavaStrict", tickLimit = 40)
    public void factualCornerLavaUsesUntriedReverseAndRestartsExactly(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreFactualCornerLavaGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos corner = start.north();
        BlockPos eastBody = corner.east();
        BlockPos lava = corner.east(2);
        BlockPos westFresh = corner.west();

        // Finish one factual north step at this corner. The newly published east leg sees lava,
        // south is the old open tunnel, north is unbreakable, and only geometric reverse west is
        // fresh safe work. This is the minimal topology that reproduces the missing-reverse bug
        // exposed by the seed-3000 iron search; the artifact did not record the hidden west block.
        world.setBlockState(corner, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(corner.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(eastBody, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(eastBody.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(corner.north(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(corner.north().up(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(westFresh, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(westFresh.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos sealed : new BlockPos[]{
                lava.east(), lava.north(), lava.south(), lava.up()}) {
            world.setBlockState(sealed, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, start)
                        && Standability.isStandable(world, corner),
                "factual-corner fixture has the wrong supported corridor");
        assertStrictCapabilities(context, bot);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "1");
        OreDigTask initial = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        initial.start(bot);
        initial.tick(bot);
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, corner, "ore_factual_corner_fixture"),
                "fixture could not complete the final factual north step");
        require(context, ObservableWorldQuery.canObserveBlock(bot, lava),
                "factual-corner lava was not visible through the open east body column");
        initial.tick(bot);

        Map<String, String> published = initial.checkpoint();
        int publishedBudget = Integer.parseInt(published.get("budget_used"));
        int publishedProgress = Integer.parseInt(published.get("last_progress_budget"));
        require(context, initial.state() == TaskState.RUNNING
                        && "1".equals(published.get("direction"))
                        && "1".equals(published.get("leg"))
                        && "48".equals(published.get("steps_left"))
                        && "48".equals(published.get("leg_length"))
                        && encode(corner).equals(published.get("face"))
                        && encode(corner).equals(published.get("boundary_reroute_origin"))
                        && encode(start).equals(published.get("controlled_strip_rear"))
                        && publishedBudget == 2
                        && publishedProgress == publishedBudget
                        && OreDigTask.inspectCheckpoint(published).isPresent(),
                "factual corner did not atomically publish its east successor: " + published);
        Map<String, String> forgedRear = new LinkedHashMap<>(published);
        forgedRear.put("controlled_strip_rear", encode(corner.east()));
        require(context, OreDigTask.inspectCheckpoint(forgedRear).isEmpty(),
                "checkpoint accepted a forged factual rear outside the perpendicular invariant");

        initial.cancel(bot, "gametest_corner_before_lava_restart");
        OreDigTask rerouted = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, published);
        rerouted.start(bot);
        require(context, Integer.parseInt(rerouted.checkpoint().get("budget_used"))
                        == publishedBudget,
                "corner restart reset the hard mining budget");
        float healthBefore = bot.getHealth();
        int deathBaseline = deathCount(bot);
        rerouted.tick(bot);

        Map<String, String> west = rerouted.checkpoint();
        require(context, rerouted.state() == TaskState.RUNNING
                        && "3".equals(west.get("direction"))
                        && "1".equals(west.get("leg"))
                        && "48".equals(west.get("steps_left"))
                        && encode(corner).equals(west.get("face"))
                        && encode(corner).equals(west.get("boundary_reroute_origin"))
                        && !west.containsKey("controlled_strip_rear")
                        && Integer.parseInt(west.get("budget_used")) == publishedBudget + 1
                        && Integer.parseInt(west.get("last_progress_budget")) == publishedProgress
                        && OreDigTask.inspectCheckpoint(west).isPresent(),
                "east lava did not select the untried factual west column: " + west);
        require(context, bot.getBlockPos().equals(corner)
                        && world.getBlockState(lava).isOf(Blocks.LAVA)
                        && world.getBlockState(eastBody).isAir()
                        && world.getBlockState(westFresh).isOf(Blocks.STONE),
                "corner reroute moved the bot or mutated protected terrain");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore
                        && deathCount(bot) == deathBaseline
                        && !bot.isInLava() && !bot.isOnFire()
                        && bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle(),
                "corner reroute lost health or retained an action producer");

        rerouted.cancel(bot, "gametest_corner_after_lava_restart");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, west);
        restored.start(bot);
        Map<String, String> restarted = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && "3".equals(restarted.get("direction"))
                        && "48".equals(restarted.get("steps_left"))
                        && encode(corner).equals(restarted.get("boundary_reroute_origin"))
                        && !restarted.containsKey("controlled_strip_rear")
                        && Integer.parseInt(restarted.get("budget_used"))
                        == publishedBudget + 1
                        && Integer.parseInt(restarted.get("last_progress_budget"))
                        == publishedProgress,
                "post-reroute restart changed its finite cursor or progress clock: " + restarted);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    private static int countCopperWall(net.minecraft.server.world.ServerWorld world,
                                       BlockPos start) {
        int remaining = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                if (world.getBlockState(start.west(2).add(0, dy, dz)).isOf(Blocks.COPPER_ORE)) {
                    remaining++;
                }
            }
        }
        return remaining;
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCloseBreakStrict", tickLimit = 700)
    public void exactTunnelStepAndQueuedHighWorkPoseArePhysicallyRecovered(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreCloseBreakGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos tunnelStep = start.east();
        BlockPos shaft = start.east(2);
        BlockPos lowerOre = shaft.up(2);
        BlockPos upperOre = shaft.south().up(3);
        BlockPos upperWorkPose = upperOre.down().east();
        BlockPos riseStep = upperWorkPose.south().down();

        // The lower coal still requires the exact tunnel step. Its diagonally connected upper vein
        // member is beyond the low break envelope, so provide a real two-step ascent to a high side
        // work pose. A direct low-shaft break is forbidden even though the ore is inside eye reach.
        world.setBlockState(lowerOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(upperOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(riseStep.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(riseStep, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(riseStep.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(upperWorkPose.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(upperWorkPose, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(upperWorkPose.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        require(context, !riseStep.up(2).equals(upperOre),
                "queued high staircase cannot put its second jump head inside the ore");
        require(context, horizontalChebyshev(start, lowerOre) == 2,
                "fixture must start outside the recoverable horizontal break envelope");
        require(context, ObservableWorldQuery.canObserveBlock(bot, lowerOre)
                        && ObservableWorldQuery.canObserveBlock(bot, upperOre),
                "stacked coal fixture must be strictly observable");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_exact_step_overhead_vein"));
        AtomicBoolean enteredTunnelStep = new AtomicBoolean();
        AtomicBoolean openedLowerFromShaft = new AtomicBoolean();
        AtomicBoolean openedUpperFromHighWorkPose = new AtomicBoolean();
        AtomicBoolean capturedUpperWorkPoseBeforeOcclusion = new AtomicBoolean();
        String encodedUpperWorkPose = encode(upperOre) + "@" + encode(upperWorkPose);

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (bot.getBlockPos().equals(tunnelStep)) {
                enteredTunnelStep.set(true);
            }
            Map<String, String> live = task.checkpoint();
            boolean retainedUpperPose = encodedUpperWorkPose.equals(
                    live.get("remembered_high_work_poses"));
            if (retainedUpperPose) {
                capturedUpperWorkPoseBeforeOcclusion.set(true);
            }
            if (encode(lowerOre).equals(live.get("active_break_pos"))) {
                require(context, bot.getBlockPos().equals(shaft),
                        "lower coal opened outside its exact drop shaft: bot="
                                + bot.getBlockPos().toShortString());
                openedLowerFromShaft.set(true);
            }
            if (encode(lowerOre).equals(live.get("pending_pickup_pos"))) {
                require(context, retainedUpperPose,
                        "newly exposed upper work pose was not persisted before pickup movement: "
                                + live);
            }
            if (encode(upperOre).equals(live.get("active_break_pos"))) {
                require(context, bot.getBlockPos().equals(upperWorkPose),
                        "queued high coal opened before its side work pose: bot="
                                + bot.getBlockPos().toShortString());
                openedUpperFromHighWorkPose.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, enteredTunnelStep.get() && openedLowerFromShaft.get()
                            && openedUpperFromHighWorkPose.get()
                            && capturedUpperWorkPoseBeforeOcclusion.get(),
                    "fixture did not exercise tunnel entry and queued high work-pose recovery");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 2,
                    "expected exactly two physically recovered coal, got "
                            + InventoryAction.countItem(bot, Items.COAL));
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) >= pickupBaseline + 2,
                    "stacked coal bypassed vanilla pickup statistics");
            require(context, !task.checkpoint().containsKey("pending_pickup_pos")
                            && !task.checkpoint().containsKey("active_break_pos"),
                    "completed stacked coal retained finite mining debt: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreHighPoseRestartStrict", tickLimit = 900)
    public void restoredObservedHighWorkPoseRoutesWithoutDigging(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHighPoseRestartGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos ore = start.up(3);
        BlockPos workPose = ore.down().east();
        BlockPos riseStep = workPose.south().down();

        world.setBlockState(riseStep.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(riseStep, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(riseStep.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);

        require(context, bot.getBlockPos().equals(start)
                        && OreDigTask.inspectApproachGoalFor(bot, world, ore) == null,
                "direct-under restart must not have a live side-pose observation");

        String encodedRememberedPose = encode(ore) + "@" + encode(workPose);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encodedRememberedPose);
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "valid remembered high-work-pose checkpoint was rejected: " + checkpoint);

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_high_pose_restart"));
        AtomicBoolean retainedRememberedPose = new AtomicBoolean();
        AtomicBoolean enteredRiseStep = new AtomicBoolean();
        AtomicBoolean enteredWorkPose = new AtomicBoolean();
        AtomicBoolean openedFromWorkPose = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            if (encodedRememberedPose.equals(live.get("remembered_high_work_poses"))) {
                retainedRememberedPose.set(true);
            }
            if (bot.getBlockPos().equals(riseStep)) {
                enteredRiseStep.set(true);
            }
            if (bot.getBlockPos().equals(workPose)) {
                enteredWorkPose.set(true);
            }
            if (encode(ore).equals(live.get("active_break_pos"))) {
                require(context, retainedRememberedPose.get() && enteredWorkPose.get(),
                        "restored high ore opened without retaining and reaching its factual pose");
                require(context, bot.getBlockPos().equals(workPose),
                        "high ore opened outside its recovered side work pose: bot="
                                + bot.getBlockPos().toShortString());
                openedFromWorkPose.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, retainedRememberedPose.get() && enteredRiseStep.get()
                            && enteredWorkPose.get() && openedFromWorkPose.get(),
                    "fixture did not restore, route to, and mine from the observed high work pose");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "restored high-pose coal was not physically recovered");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) > pickupBaseline,
                    "restored high-pose coal bypassed vanilla pickup statistics");
            require(context, world.getBlockState(riseStep.down()).isOf(Blocks.STONE)
                            && world.getBlockState(workPose.down()).isOf(Blocks.STONE),
                    "remembered work-pose route dug through its staircase supports");
            require(context, !task.checkpoint().containsKey("remembered_high_work_poses")
                            && !task.checkpoint().containsKey("active_break_pos")
                            && !task.checkpoint().containsKey("pending_pickup_pos"),
                    "completed restored high-pose task retained finite debt: "
                            + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreHighPoseLeaseStrict", tickLimit = 180)
    public void rememberedHighWorkPoseOwnerLeaseExpiresAcrossSuccessfulReplans(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHighPoseLeaseGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos ore = start.east(14).up(3);
        BlockPos workPose = ore.down().west();

        // Keep each exact route alive for at least the five-tick successful-path cooldown. Every
        // sixth tick the fixture returns the bot to the factual start and asks for another
        // successful no-dig route to the same owner. The absolute owner lease must still expire.
        for (int x = 1; x <= 10; x++) {
            BlockPos cell = start.east(x);
            world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos firstRise = start.east(11).up();
        BlockPos secondRise = start.east(12).up(2);
        world.setBlockState(firstRise.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(firstRise, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(firstRise.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(secondRise.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(secondRise, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(secondRise.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(workPose.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encode(ore) + "@" + encode(workPose));
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "route-lease fixture checkpoint was rejected");
        assertStrictCapabilities(context, bot);

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        enqueueVeinForFixture(task, ore);
        int deathBaseline = deathCount(bot);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger successfulRouteStarts = new AtomicInteger();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            int callback = callbacks.incrementAndGet();
            boolean replanBoundary = (callback - 1) % 6 == 0;
            if (replanBoundary) {
                bot.getActionPack().stopAll();
                bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                        Set.of(), 180.0F, 0.0F, true);
                bot.setOnGround(true);
            }
            task.tick(bot);

            if (replanBoundary && !EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUuid(), ore, bot.getServer().getTicks())) {
                require(context, workPose.equals(bot.getActionPack().activePathGoal()),
                        "remembered route was not accepted as an exact successful replan: "
                                + bot.getActionPack().activePathGoal());
                successfulRouteStarts.incrementAndGet();
            }
            if (!EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUuid(), ore, bot.getServer().getTicks())) {
                require(context, callback <= 96,
                        "successful remembered routes renewed the finite owner lease forever");
                return;
            }

            Map<String, String> terminalCheckpoint = task.checkpoint();
            int leaseAge = Integer.parseInt(terminalCheckpoint.get("budget_used"));
            require(context, successfulRouteStarts.get() >= 3,
                    "fixture did not issue repeated successful routes before lease expiry");
            require(context, leaseAge > 80 && leaseAge <= 86,
                    "remembered owner expired outside its absolute lease: " + leaseAge);
            require(context, task.state() == TaskState.RUNNING,
                    "finite owner abandonment terminated the whole OreDig task: "
                            + task.state() + ":" + task.failureReason());
            require(context, world.getBlockState(ore).isOf(Blocks.COAL_ORE),
                    "no-dig remembered route modified its finite ore owner");
            require(context, !terminalCheckpoint.containsKey("remembered_high_work_poses"),
                    "expired remembered owner survived in the durable ledger");
            require(context, inspectVeinQueueForFixture(task).isEmpty(),
                    "expired remembered vein owner remained queued");
            require(context, bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "owner lease expiry retained an action controller");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreHighPoseCapacityStrict", tickLimit = 30)
    public void rememberedHighWorkPoseCapacityEvictsDeterministicFarthestUnpinnedOwner(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHighPoseCapacityGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos face = fixture.start();
        Map<BlockPos, BlockPos> initial = new LinkedHashMap<>();
        BlockPos routeOwner = face.add(20, 3, 19);
        BlockPos targetOwner = face.add(19, 3, 20);
        BlockPos queueOwner = face.add(-20, 3, 19);
        BlockPos expectedEviction = face.add(19, 3, -20);
        BlockPos equalDistanceSurvivor = face.add(-19, 3, 20);
        for (BlockPos owner : new BlockPos[]{
                routeOwner, targetOwner, queueOwner,
                expectedEviction, equalDistanceSurvivor}) {
            initial.put(owner.toImmutable(), owner.down().east().toImmutable());
        }
        for (int x = -4; x <= 4 && initial.size() < 64; x++) {
            for (int z = -4; z <= 4 && initial.size() < 64; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                BlockPos owner = face.add(x, 3, z);
                initial.put(owner.toImmutable(), owner.down().east().toImmutable());
            }
        }
        require(context, initial.size() == 64,
                "capacity fixture did not create exactly 64 remembered owners");

        StringBuilder encoded = new StringBuilder();
        initial.forEach((owner, pose) -> {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(encode(owner)).append('@').append(encode(pose));
        });
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(face, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("remembered_high_work_poses", encoded.toString());
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "64-entry remembered ledger fixture was rejected");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        setBlockPosFieldForFixture(task, "rememberedHighWorkPoseRouteOwner", routeOwner);
        setBlockPosFieldForFixture(task, "targetOre", targetOwner);
        enqueueVeinForFixture(task, queueOwner);
        BlockPos candidate = face.up(3);
        BlockPos candidatePose = candidate.down().east();
        rememberHighWorkPoseForFixture(task, bot, candidate, candidatePose);

        Map<BlockPos, BlockPos> live = inspectRememberedHighWorkPosesForFixture(task);
        require(context, live.size() == 64,
                "runtime remembered ledger did not return to its exact cap: " + live.size());
        require(context, candidatePose.equals(live.get(candidate)),
                "fresh near observation was starved by a full stale ledger");
        require(context, live.containsKey(routeOwner)
                        && live.containsKey(targetOwner)
                        && live.containsKey(queueOwner),
                "capacity eviction removed a pinned finite owner");
        require(context, !live.containsKey(expectedEviction)
                        && live.containsKey(equalDistanceSurvivor),
                "equal-distance eviction did not use deterministic numeric x/y/z order");

        Map<String, String> durable = task.checkpoint();
        String durableEntries = durable.get("remembered_high_work_poses");
        require(context, durableEntries != null
                        && durableEntries.split(";", -1).length == 64
                        && OreDigTask.inspectCheckpoint(durable).isPresent(),
                "evicted runtime ledger did not publish a valid exact-cap checkpoint");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreQueuedHighCatchStrict", tickLimit = 1000)
    public void queuedHighOreWithoutWorkPoseStaysIntactAndSearchContinues(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreQueuedHighReachGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos shaft = start.east(2);
        BlockPos lowerOre = shaft.up(2);
        BlockPos upperOre = shaft.up(3);
        BlockPos higherOre = shaft.up(4);
        BlockPos highestOre = shaft.up(5);
        BlockPos alternativeOre = start.north(4).up();

        // Reproduce the seed-3000 authorization boundary: the top queued ore is exactly dy=+5 and
        // still inside vanilla reach, but no observed high side pose or staircase exists. The whole
        // high chain must remain intact after the recoverable lower ore, and mining must continue at
        // a separate finite ore instead of creating an unrecoverable ItemEntity debt.
        world.setBlockState(lowerOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(upperOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(higherOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(highestOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(alternativeOre.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(alternativeOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, ObservableWorldQuery.canObserveBlock(bot, lowerOre)
                        && ObservableWorldQuery.canObserveBlock(bot, upperOre)
                        && ObservableWorldQuery.canObserveBlock(bot, higherOre)
                        && ObservableWorldQuery.canObserveBlock(bot, highestOre),
                "stacked queued-ore fixture must begin strictly observable");
        require(context, bot.getEyePos().squaredDistanceTo(highestOre.toCenterPos()) <= 20.25D,
                "dy=+5 queued ore must begin inside the former direct-break reach shortcut");
        require(context, ObservableWorldQuery.canObserveBlock(bot, alternativeOre)
                        && Standability.isStandable(world, alternativeOre.south().down()),
                "replacement coal must have a visible raised pedestal and safe side work pose");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_queued_high_catch"));
        AtomicBoolean openedRecoverableLower = new AtomicBoolean();
        AtomicBoolean openedAlternative = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            String active = live.get("active_break_pos");
            if (encode(lowerOre).equals(active)) {
                require(context, bot.getBlockPos().equals(shaft),
                        "recoverable lower ore opened away from its close work pose: bot="
                                + bot.getBlockPos().toShortString());
                openedRecoverableLower.set(true);
            }
            if (encode(alternativeOre).equals(active)) {
                openedAlternative.set(true);
            }
            require(context, !encode(upperOre).equals(active)
                            && !encode(higherOre).equals(active)
                            && !encode(highestOre).equals(active),
                    "high queued ore entered active break without a recoverable work pose: " + active);
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, openedRecoverableLower.get() && openedAlternative.get(),
                    "task did not continue from the rejected high chain to a safe ore");
            require(context, world.getBlockState(upperOre).isOf(Blocks.COAL_ORE)
                            && world.getBlockState(higherOre).isOf(Blocks.COAL_ORE)
                            && world.getBlockState(highestOre).isOf(Blocks.COAL_ORE),
                    "an unproven high-shaft ore was physically opened");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 2,
                    "expected two physically recovered safe coal, got "
                            + InventoryAction.countItem(bot, Items.COAL));
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) >= pickupBaseline + 2,
                    "safe replacement coal bypassed vanilla pickup statistics");
            require(context, !live.containsKey("pending_pickup_pos")
                            && !live.containsKey("active_break_pos"),
                    "completed high-catch rejection retained finite mining debt: " + live);
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreQueuedBeyondReachStrict", tickLimit = 1200)
    public void queuedOreBeyondVanillaReachIsReleasedWithoutCursorLivelock(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreQueuedBeyondReachGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos shaft = fixture.start().east(2);
        BlockPos unreachableOre = shaft.up(6);
        for (int dy = 2; dy <= 6; dy++) {
            world.setBlockState(shaft.up(dy), Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        }
        require(context, unreachableOre.getY() - shaft.getY() == 6,
                "queued release fixture must end beyond vanilla reach");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 5);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_queued_beyond_reach_release"));
        AtomicBoolean recoveredReachableLower = new AtomicBoolean();
        AtomicInteger ticksAfterLower = new AtomicInteger();
        AtomicReference<BlockPos> previous = new AtomicReference<>(bot.getBlockPos().toImmutable());

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.getAndSet(now.toImmutable());
            int movement = Math.max(
                    Math.max(Math.abs(now.getX() - before.getX()),
                            Math.abs(now.getY() - before.getY())),
                    Math.abs(now.getZ() - before.getZ()));
            require(context, movement <= 1,
                    "queued ore release caused non-adjacent movement: from="
                            + before.toShortString() + " to=" + now.toShortString());

            int coal = InventoryAction.countItem(bot, Items.COAL);
            boolean highChainIntact = true;
            for (int dy = 3; dy <= 6; dy++) {
                highChainIntact &= world.getBlockState(shaft.up(dy)).isOf(Blocks.COAL_ORE);
            }
            if (coal >= 1 && highChainIntact) {
                recoveredReachableLower.set(true);
            }
            if (!recoveredReachableLower.get()) {
                return;
            }
            require(context, coal == 1,
                    "ore beyond vanilla reach entered inventory: " + coal);
            require(context, highChainIntact,
                    "queued release modified a high ore outside the recoverable break envelope");

            Map<String, String> live = task.checkpoint();
            boolean cursorResumed = Integer.parseInt(live.get("direction")) >= 0
                    && Integer.parseInt(live.get("steps_left")) > 0;
            if (cursorResumed) {
                require(context, now.getY() == shaft.getY(),
                        "queued release abandoned the factual mining level: "
                                + now.toShortString());
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticksAfterLower.incrementAndGet() > 40) {
                context.throwGameTestException(
                        "queued ore beyond reach retained the vein head for over 40 ticks: "
                                + live + " bot=" + now.toShortString());
            }
        });
    }

    private static int horizontalChebyshev(BlockPos from, BlockPos to) {
        return Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getZ() - to.getZ()));
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupCornerStrict", tickLimit = 400)
    public void hiddenDiagonalDropUsesExactLRouteWithoutChangingCornerWalls(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupCornerGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos turn = start.west();
        BlockPos dropCell = turn.north();
        BlockPos blockedCorner = start.north();

        // Reproduce seed3000's final tunnel face: the remembered break cell is diagonally
        // adjacent, the direct line is blocked, and the already-open west->north L is the only
        // ordinary player route. Recovery may walk it but may not mine either corner wall.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (BlockPos feet : new BlockPos[]{start, turn, dropCell}) {
            for (int dy = 0; dy <= 2; dy++) {
                world.setBlockState(feet.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        ItemEntity drop = new ItemEntity(world,
                dropCell.getX() + 0.5D, dropCell.getY() + 0.1D, dropCell.getZ() + 0.5D,
                new ItemStack(Items.COAL));
        drop.setVelocity(Vec3d.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(true);
        // Keep vanilla's generous nearby-player pickup check from consuming the diagonal item
        // before OreDig has started its remembered-cell route. Release it only after the bot has
        // physically entered the L turn below.
        drop.setPickupDelayInfinite();
        require(context, world.spawnEntity(drop), "failed to spawn hidden corner coal drop");
        require(context, !ObservableWorldQuery.canObserveEntity(bot, drop),
                "corner fixture did not hide the diagonal ItemEntity");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("pending_pickup_pos", encode(dropCell));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.COAL));
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_corner"));
        AtomicBoolean sawPendingLedger = new AtomicBoolean();
        AtomicBoolean visitedTurn = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            if (encode(dropCell).equals(live.get("pending_pickup_pos"))) {
                sawPendingLedger.set(true);
            }
            if (bot.getBlockPos().equals(turn)) {
                visitedTurn.set(true);
                if (released.compareAndSet(false, true)) {
                    drop.resetPickupDelay();
                }
            }
            require(context, world.getBlockState(blockedCorner).isOf(Blocks.STONE)
                            && world.getBlockState(blockedCorner.up()).isOf(Blocks.STONE),
                    "pickup recovery modified the diagonal corner wall");
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawPendingLedger.get(),
                    "corner recovery never restored the durable pickup ledger");
            require(context, visitedTurn.get(),
                    "pickup did not traverse the required west->north L turn");
            require(context, released.get(),
                    "corner drop was collected before the L-route release boundary");
            require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                    "expected exactly one physically collected coal");
            require(context, bot.getStatHandler().getStat(
                            Stats.PICKED_UP.getOrCreateStat(Items.COAL)) > pickupBaseline,
                    "corner coal entered inventory without vanilla pickup statistics");
            require(context, !live.containsKey("pending_pickup_pos"),
                    "completed corner pickup retained its durable debt");
            require(context, !drop.isAlive(), "picked corner coal entity remained alive");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupDiagonalStrict", tickLimit = 700)
    public void diagonalPhysicalPickupClearsDebtAndContinuesMining(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupDiagonalGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos pendingOre = fixture.start().east().north().up();
        BlockPos nextOre = fixture.start().north(3).up();
        var world = bot.getServerWorld();
        world.setBlockState(nextOre, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 2, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("pending_pickup_pos", encode(pendingOre));
        checkpoint.put("pending_pickup_inventory", "1");
        checkpoint.put("pending_pickup_started_budget", "0");

        ItemEntity secondDrop = new ItemEntity(world,
                bot.getX(), bot.getY() + 0.1D, bot.getZ(),
                new ItemStack(Items.RAW_IRON));
        secondDrop.setVelocity(Vec3d.ZERO);
        secondDrop.resetPickupDelay();
        require(context, world.spawnEntity(secondDrop),
                "failed to spawn the physical second raw-iron drop");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON));
        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 2, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_diagonal"));
        AtomicBoolean sawDiagonalPickupPending = new AtomicBoolean();
        AtomicBoolean sawNextMiningAfterClear = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            Map<String, String> live = task.checkpoint();
            int rawIron = InventoryAction.countItem(bot, Items.RAW_IRON);
            if (rawIron >= 2 && encode(pendingOre).equals(live.get("pending_pickup_pos"))) {
                require(context, bot.getBlockPos().getSquaredDistance(pendingOre) > 2.0D,
                        "fixture did not reproduce the diagonal pickup distance");
                require(context, world.getBlockState(nextOre).isOf(Blocks.IRON_ORE),
                        "next iron ore opened before the prior physical-drop debt cleared");
                sawDiagonalPickupPending.set(true);
            }
            if (encode(nextOre).equals(live.get("active_break_pos"))) {
                require(context, sawDiagonalPickupPending.get(),
                        "next iron mining started before the diagonal pickup was observed");
                require(context, !live.containsKey("pending_pickup_pos"),
                        "next iron mining started with stale physical-drop debt");
                sawNextMiningAfterClear.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, sawDiagonalPickupPending.get(),
                        "second raw iron never exercised the squared-distance=3 boundary");
                require(context, sawNextMiningAfterClear.get(),
                        "task did not continue to the next ore after clearing pickup debt");
                require(context, rawIron == 3,
                        "expected baseline plus two physically collected raw iron, got " + rawIron);
                require(context, bot.getStatHandler().getStat(
                                Stats.PICKED_UP.getOrCreateStat(Items.RAW_IRON)) > pickupBaseline,
                        "second raw iron did not enter through vanilla pickup statistics");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupStrict", tickLimit = 900)
    public void consecutiveEyeHeightDiamondsWaitForEachPhysicalPickup(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupPairGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos first = fixture.start().north(4).up();
        BlockPos second = fixture.start().north(5).up();
        bot.getServerWorld().setBlockState(first, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(second, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        int pickupBaseline = bot.getStatHandler().getStat(
                Stats.PICKED_UP.getOrCreateStat(Items.DIAMOND));
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_pair"));
        AtomicBoolean sawFirstPendingWithoutPickup = new AtomicBoolean();
        AtomicBoolean sawFirstPhysicalPickup = new AtomicBoolean();
        AtomicBoolean sawSecondMiningAfterFirstPickup = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            int diamonds = InventoryAction.countItem(bot, Items.DIAMOND);
            Map<String, String> checkpoint = task.checkpoint();

            if (bot.getServerWorld().getBlockState(first).isAir() && diamonds == 0) {
                // ActionPack removes the block before OreDig's next task tick promotes
                // active_break_pos into pending_pickup_pos. Both are valid ledger states, but
                // neither may hand control to the second ore.
                boolean activeBreakObserved = encode(first).equals(checkpoint.get("active_break_pos"));
                boolean pendingPickupObserved = encode(first).equals(checkpoint.get("pending_pickup_pos"));
                require(context, activeBreakObserved || pendingPickupObserved,
                        "first broken ore disappeared from the target-drop ledger: " + checkpoint);
                if (pendingPickupObserved) {
                    sawFirstPendingWithoutPickup.set(true);
                }
                require(context, !bot.getServerWorld().getBlockState(second).isAir(),
                        "second eye-height ore broke before the first drop entered inventory");
                require(context, !encode(second).equals(checkpoint.get("active_break_pos")),
                        "second eye-height ore started before the first drop entered inventory");
            }

            if (diamonds >= 1 && !sawFirstPhysicalPickup.get()) {
                // ItemEntity launch velocity is advanced before the GameTest callback and can move
                // farther than an arbitrary last-sampled-position radius. The vanilla PICKED_UP
                // stat is the authoritative collision-pickup receipt; strict capabilities above
                // already prove that forced pickup is unavailable.
                require(context, bot.getStatHandler().getStat(
                                Stats.PICKED_UP.getOrCreateStat(Items.DIAMOND))
                                >= pickupBaseline + 1,
                        "first diamond did not enter through vanilla pickup statistics");
                sawFirstPhysicalPickup.set(true);
            }

            if (encode(second).equals(checkpoint.get("active_break_pos"))) {
                require(context, diamonds >= 1 && sawFirstPhysicalPickup.get(),
                        "second ore mining started before first physical pickup");
                sawSecondMiningAfterFirstPickup.set(true);
            }
            if (bot.getServerWorld().getBlockState(second).isAir()) {
                require(context, diamonds >= 1 && sawFirstPhysicalPickup.get(),
                        "second ore broke before first physical pickup");
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, sawFirstPendingWithoutPickup.get(),
                        "fixture never exercised the pending-pickup pause between adjacent ores");
                require(context, sawFirstPhysicalPickup.get(),
                        "first diamond was not observed entering through physical recovery");
                require(context, sawSecondMiningAfterFirstPickup.get(),
                        "second ore never entered the accounted mining state");
                require(context, diamonds == 2,
                        "expected exactly two collision-picked diamonds, got " + diamonds);
                require(context, bot.getStatHandler().getStat(
                                Stats.PICKED_UP.getOrCreateStat(Items.DIAMOND))
                                >= pickupBaseline + 2,
                        "second diamond did not enter through vanilla pickup statistics");
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupStrict", tickLimit = 700)
    public void footLevelDiamondDropIsRecoveredByWalkingIntoItsCell(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupFootGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().north(4);
        bot.getServerWorld().setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_foot"));
        AtomicBoolean sawPendingWithoutPickup = new AtomicBoolean();
        AtomicBoolean sawVanillaDrop = new AtomicBoolean();
        AtomicReference<Vec3d> lastVanillaDropPosition = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            int diamonds = InventoryAction.countItem(bot, Items.DIAMOND);
            if (diamonds == 0) {
                nearestDiamondDropPosition(bot, ore).ifPresent(position -> {
                    sawVanillaDrop.set(true);
                    lastVanillaDropPosition.set(position);
                });
            }
            if (bot.getServerWorld().getBlockState(ore).isAir() && diamonds == 0) {
                Map<String, String> checkpoint = task.checkpoint();
                boolean activeBreakObserved = encode(ore).equals(checkpoint.get("active_break_pos"));
                boolean pendingPickupObserved = encode(ore).equals(checkpoint.get("pending_pickup_pos"));
                require(context, activeBreakObserved || pendingPickupObserved,
                        "foot-level ore drop disappeared from the target-drop ledger: " + checkpoint);
                if (pendingPickupObserved) {
                    sawPendingWithoutPickup.set(true);
                }
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, sawPendingWithoutPickup.get(),
                        "foot-level fixture did not separate breaking from pickup");
                require(context, sawVanillaDrop.get(),
                        "foot-level diamond ItemEntity was never observable before pickup");
                require(context, diamonds == 1,
                        "expected exactly one foot-level diamond, got " + diamonds);
                Vec3d lastDrop = lastVanillaDropPosition.get();
                require(context, lastDrop != null
                                && bot.getPos().squaredDistanceTo(lastDrop) <= 4.0D,
                        "foot-level diamond entered inventory away from the last vanilla drop position");
                // Vanilla loot has launch velocity and can roll toward the miner, so requiring the
                // original block cell would reject a genuine collision pickup. The regression
                // boundary is that the bot moved and met the observed ItemEntity.
                require(context, !bot.getBlockPos().equals(fixture.start()),
                        "bot did not physically leave the start cell to recover the drop");
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupStrict", tickLimit = 800)
    public void pendingPickupCheckpointResumesBeforeAnyNewMining(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().north(4).up();
        bot.getServerWorld().setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        AtomicReference<OreDigTask> active = new AtomicReference<>(
                new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1));
        TaskManager.INSTANCE.assign(bot, active.get(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_restart"));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean restartedFromLastSeen = new AtomicBoolean();
        AtomicBoolean displacedDropAfterRestart = new AtomicBoolean();
        AtomicBoolean sawDropAfterRestart = new AtomicBoolean();
        AtomicReference<ItemEntity> displacedDrop = new AtomicReference<>();
        AtomicReference<Vec3d> lastDropAfterRestart = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            OreDigTask task = active.get();
            failIfTerminalError(context, task);
            int diamonds = InventoryAction.countItem(bot, Items.DIAMOND);
            Map<String, String> checkpoint = task.checkpoint();

            if (!restarted.get()
                    && diamonds == 0
                    && encode(ore).equals(checkpoint.get("pending_pickup_pos"))) {
                require(context, bot.getServerWorld().getBlockState(ore).isAir(),
                        "pickup checkpoint was written before the target ore broke");
                require(context, "0".equals(checkpoint.get("pending_pickup_inventory")),
                        "pickup checkpoint stored the wrong inventory baseline: " + checkpoint);
                int budgetBefore = Integer.parseInt(checkpoint.get("budget_used"));
                String pickupStartedBefore = checkpoint.get("pending_pickup_started_budget");

                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_pickup_restart_boundary");
                OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
                TaskManager.INSTANCE.assign(bot, restored,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_restored"));
                Map<String, String> after = restored.checkpoint();
                require(context, encode(ore).equals(after.get("pending_pickup_pos"))
                                && "0".equals(after.get("pending_pickup_inventory"))
                                && pickupStartedBefore.equals(after.get("pending_pickup_started_budget"))
                                && Integer.parseInt(after.get("budget_used")) >= budgetBefore,
                        "pending pickup changed across checkpoint restore: before="
                                + checkpoint + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (restarted.get() && diamonds == 0) {
                nearestDiamondDrop(bot, ore).ifPresent(drop -> {
                    if (displacedDropAfterRestart.compareAndSet(false, true)) {
                        // Make the vanilla launch-velocity edge deterministic: a restored task
                        // must follow the surviving entity after it leaves the mined block instead
                        // of waiting forever at the stale checkpoint coordinate.
                        Vec3d displaced = Vec3d.ofBottomCenter(ore.down().east(2)).add(0.0D, 0.1D, 0.0D);
                        drop.refreshPositionAndAngles(
                                displaced.x, displaced.y, displaced.z, 0.0F, 0.0F);
                        drop.setVelocity(Vec3d.ZERO);
                        drop.setPickupDelayInfinite();
                        displacedDrop.set(drop);
                    }
                    sawDropAfterRestart.set(true);
                    lastDropAfterRestart.set(drop.getPos());
                });
            }
            String lastSeen = checkpoint.get("pending_pickup_last_seen_pos");
            if (restarted.get() && displacedDropAfterRestart.get()
                    && !restartedFromLastSeen.get()
                    && lastSeen != null && !lastSeen.equals(encode(ore))) {
                String expectedLastSeen = encode(ore.down().east(2));
                require(context, expectedLastSeen.equals(lastSeen),
                        "moving drop published the wrong durable last-seen cell: " + checkpoint);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_pickup_last_seen_restart_boundary");
                OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
                TaskManager.INSTANCE.assign(bot, restored,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_ore_pickup_last_seen_restored"));
                require(context, lastSeen.equals(
                                restored.checkpoint().get("pending_pickup_last_seen_pos")),
                        "moving drop last-seen cell changed across checkpoint restore: before="
                                + checkpoint + " after=" + restored.checkpoint());
                active.set(restored);
                restartedFromLastSeen.set(true);
                ItemEntity heldDrop = displacedDrop.get();
                require(context, heldDrop != null && heldDrop.isAlive(),
                        "moving drop vanished before its durable restart boundary");
                heldDrop.resetPickupDelay();
                return;
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, restarted.get(),
                        "ore completed without exercising pending-pickup restore");
                require(context, restartedFromLastSeen.get(),
                        "ore completed without restoring the moving-drop last-seen cell");
                require(context, sawDropAfterRestart.get(),
                        "restored task never pursued the surviving vanilla ItemEntity");
                require(context, displacedDropAfterRestart.get(),
                        "fixture never displaced the surviving ItemEntity from the mined block");
                require(context, diamonds == 1,
                        "restored pickup produced the wrong diamond count: " + diamonds);
                Vec3d lastDrop = lastDropAfterRestart.get();
                require(context, lastDrop != null
                                && bot.getPos().squaredDistanceTo(lastDrop) <= 4.0D,
                        "restored pickup completed away from its observed ItemEntity");
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupFallbackStrict", tickLimit = 30)
    public void unreachableVisibleLastSeenFallsBackToReachableBreakCell(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupFallbackGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos breakCell = start.north(3);
        BlockPos unreachableLedge = start.east(2).up(3);
        world.setBlockState(
                unreachableLedge.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unreachableLedge, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unreachableLedge.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        ItemEntity drop = new ItemEntity(world,
                unreachableLedge.getX() + 0.5D,
                unreachableLedge.getY() + 0.1D,
                unreachableLedge.getZ() + 0.5D,
                new ItemStack(Items.COAL));
        drop.setVelocity(Vec3d.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(true);
        drop.setPickupDelayInfinite();
        require(context, world.spawnEntity(drop), "failed to spawn unreachable ledge drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("pending_pickup_pos", encode(breakCell));
        checkpoint.put("pending_pickup_last_seen_pos", encode(unreachableLedge));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        for (int i = 0; i < 5; i++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.RUNNING,
                "fallback ledger ended unexpectedly: " + task.failureReason());
        require(context, breakCell.equals(bot.getActionPack().activePathGoal()),
                "failed high last-seen route suppressed the reachable break-cell fallback: goal="
                        + bot.getActionPack().activePathGoal());
        require(context, encode(breakCell).equals(
                        task.checkpoint().get("pending_pickup_pos"))
                        && encode(unreachableLedge).equals(
                        task.checkpoint().get("pending_pickup_last_seen_pos")),
                "fallback movement mutated its durable pickup ledger: " + task.checkpoint());
        require(context, world.getBlockState(unreachableLedge.down()).isOf(Blocks.STONE),
                "fallback manufactured a route to the unreachable ledge");
        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupStrict", tickLimit = 400)
    public void sameColumnDropBelowMinerUsesPhysicalDescent(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupBelowGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos upper = fixture.start();
        BlockPos dropCell = upper.down();
        var world = bot.getServerWorld();

        // A server-side fake player has no client gravity. Removing its support reproduces the
        // deep-staircase case where a mined ore's ItemEntity settles in the open cell immediately
        // below while the bot remains suspended in the same X/Z column.
        world.setBlockState(dropCell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(dropCell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        bot.setNoGravity(true);
        ItemEntity drop = new ItemEntity(world,
                dropCell.getX() + 0.5D, dropCell.getY() + 0.1D, dropCell.getZ() + 0.5D,
                new ItemStack(Items.DIAMOND));
        drop.setVelocity(Vec3d.ZERO);
        require(context, world.spawnEntity(drop), "failed to spawn below-column diamond drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(upper, 1));
        checkpoint.put("pending_pickup_pos", encode(dropCell));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_below"));
        AtomicBoolean enteredDropLevel = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            if (bot.getBlockPos().getY() == dropCell.getY()) {
                enteredDropLevel.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, enteredDropLevel.get(),
                        "below-column drop was collected without entering its physical level");
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                        "expected exactly one physically recovered below-column diamond");
                require(context, bot.getBlockPos().equals(dropCell),
                        "miner did not finish in the drop cell: " + bot.getBlockPos().toShortString());
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupStrict", tickLimit = 400)
    public void elevatedDropUsesLowerAdjacentStandWithoutPillar(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupElevatedGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos dropCell = start.north(2).up();

        // The drop rests on a one-block pedestal under a low ceiling. Its own Y-level and four
        // neighbours are not valid player poses, but the lower ring is a real walkable pickup
        // surface. This is the exact geometry produced by eye-height iron on a descending stair.
        world.setBlockState(dropCell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(dropCell.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        ItemEntity drop = new ItemEntity(world,
                dropCell.getX() + 0.5D, dropCell.getY() + 0.1D, dropCell.getZ() + 0.5D,
                new ItemStack(Items.DIAMOND));
        drop.setVelocity(Vec3d.ZERO);
        require(context, world.spawnEntity(drop), "failed to spawn elevated diamond drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(start, 1));
        checkpoint.put("pending_pickup_pos", encode(dropCell));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_elevated"));
        AtomicInteger maxY = new AtomicInteger(start.getY());

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            maxY.accumulateAndGet(bot.getBlockY(), Math::max);
            if (task.state() == TaskState.COMPLETED) {
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                        "elevated diamond was not physically recovered");
                require(context, maxY.get() <= start.getY() + 1,
                        "pickup climbed above the one-block vanilla route: max_y=" + maxY.get());
                require(context, bot.getBlockY() == start.getY(),
                        "pickup did not finish on the verified lower ring: "
                                + bot.getBlockPos().toShortString());
                require(context, bot.getBlockPos().getSquaredDistance(dropCell) <= 2.0D,
                        "pickup completed away from the elevated drop: "
                                + bot.getBlockPos().toShortString());
                require(context, world.getBlockState(dropCell.down()).isOf(Blocks.STONE)
                                && world.getBlockState(dropCell.up()).isOf(Blocks.STONE),
                        "pickup modified its pedestal or ceiling to manufacture a route");
                require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                        "elevated pickup retained a stale durable ledger");
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "orePickupStrict", tickLimit = 600)
    public void airborneDropWaitsForLandingAndUsesNaturalRouteWithoutPillar(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupAirborneGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos landing = start.east(4).up(4);

        // Build a real ascending route.  The bot also carries filler blocks so the old generic
        // pickup path was able to pillar toward the transient airborne coordinate.
        for (int step = 1; step <= 4; step++) {
            BlockPos foot = start.east(step).up(step);
            world.setBlockState(foot.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(foot, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(foot.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        int fillerBaseline = InventoryAction.countItem(bot, Items.COBBLESTONE);

        ItemEntity drop = new ItemEntity(world,
                landing.getX() + 0.5D, landing.getY() + 3.1D, landing.getZ() + 0.5D,
                new ItemStack(Items.DIAMOND));
        drop.setVelocity(Vec3d.ZERO);
        drop.setNoGravity(true);
        require(context, world.spawnEntity(drop), "failed to spawn airborne diamond drop");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(start, 1));
        checkpoint.put("pending_pickup_pos", encode(landing));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");

        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_pickup_airborne"));
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger maxY = new AtomicInteger(start.getY());
        AtomicBoolean released = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, task);
            int tick = ticks.incrementAndGet();
            maxY.accumulateAndGet(bot.getBlockY(), Math::max);
            if (tick < 30) {
                require(context, bot.getBlockPos().equals(start),
                        "miner chased an unsupported airborne coordinate: "
                                + bot.getBlockPos().toShortString());
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == fillerBaseline,
                        "airborne pickup consumed pillar material before landing");
            } else if (released.compareAndSet(false, true)) {
                // Do not reuse the synthetic no-gravity entity. Under accelerated parallel
                // GameTests it can retain stale interpolation/section state after gravity is
                // restored and remain unsupported for the whole production recovery budget.
                // Replace it with a fresh supported entity at the factual landing. Entity gravity
                // itself is not the policy under test, and accelerated GameTests do not provide a
                // stable interpolation contract. The bot must still wait for this release and then
                // traverse the complete natural staircase for vanilla collision pickup.
                drop.discard();
                ItemEntity settled = new ItemEntity(world,
                        landing.getX() + 0.5D,
                        landing.getY() + 0.1D,
                        landing.getZ() + 0.5D,
                        new ItemStack(Items.DIAMOND));
                settled.setVelocity(Vec3d.ZERO);
                settled.setOnGround(true);
                settled.resetPickupDelay();
                require(context, world.spawnEntity(settled),
                        "failed to spawn released supported diamond drop");
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, released.get(), "pickup completed before the drop was released");
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 1,
                        "expected one physically recovered airborne diamond");
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == fillerBaseline,
                        "pickup route consumed filler blocks");
                require(context, maxY.get() <= landing.getY(),
                        "pickup route climbed above the settled drop: max_y=" + maxY.get());
                require(context, !task.checkpoint().containsKey("pending_pickup_pos"),
                        "completed pickup retained its durable pending ledger");
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void rareTorchEpochStopsAtFortyBeforeExtendingDarkBranch(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTorchEpochLimitGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos isolated = isolateCheckpointMiner(fixture, 40);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 64));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(isolated, 8));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "10");
        checkpoint.put("torch_placements", "40");

        OreDigTask task = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "rare OreDig extended a dark branch after forty torches");
        require(context, "ore_dig_torch_epoch_exhausted:placed=40:epoch=0"
                        .equals(task.failureReason()),
                "forty-torch boundary reported the wrong typed failure: "
                        + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.TORCH) == 64,
                "exhausted epoch consumed a forty-first torch");
        require(context, "40".equals(task.checkpoint().get("torch_placements"))
                        && "0".equals(task.checkpoint().get("resource_epoch")),
                "terminal checkpoint refreshed the exhausted resource epoch: "
                        + task.checkpoint());
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void rareDarkBranchWithoutTorchFailsWithItsExactEpoch(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTorchStockEmptyGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos isolated = isolateCheckpointMiner(fixture, 40);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(isolated, 8));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "10");
        checkpoint.put("torch_placements", "7");

        OreDigTask task = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "ore_dig_torch_epoch_exhausted:placed=7:epoch=0"
                        .equals(task.failureReason()),
                "empty torch stock continued black mining: " + task.state()
                        + ":" + task.failureReason());
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void legacyOpenCheckpointWithoutDeliveredLedgerFailsClosed(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTorchSchemaMigrationGT");
        Map<String, String> legacyRunning = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 8));
        legacyRunning.put("task_schema", "1");
        legacyRunning.remove("delivered");
        legacyRunning.remove("torch_limit");
        legacyRunning.remove("torch_placements");
        legacyRunning.remove("resource_epoch");
        legacyRunning.remove("rare_mission_target");
        legacyRunning.remove("inventory_service_used");

        OreDigTask first = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, legacyRunning);
        first.start(fixture.bot());
        require(context, first.state() == TaskState.FAILED
                        && "ore_dig_invalid_checkpoint".equals(first.failureReason())
                        && first.checkpoint().isEmpty(),
                "schema1 open batch invented delivered=0: " + first.checkpoint());

        Map<String, String> schemaTwo = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8));
        schemaTwo.put("task_schema", "2");
        schemaTwo.remove("delivered");
        schemaTwo.remove("rare_mission_target");
        schemaTwo.remove("inventory_service_used");
        schemaTwo.put("torch_placements", "17");
        schemaTwo.put("resource_epoch", "1");
        schemaTwo.put("budget_used", "123");
        schemaTwo.put("last_progress_budget", "100");
        OreDigTask schemaTwoRestored = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, schemaTwo);
        schemaTwoRestored.start(fixture.bot());
        require(context, schemaTwoRestored.state() == TaskState.FAILED
                        && "ore_dig_invalid_checkpoint".equals(
                        schemaTwoRestored.failureReason()),
                "schema2 open batch invented delivered=0");

        Map<String, String> schemaThree = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8));
        schemaThree.put("task_schema", "3");
        schemaThree.remove("delivered");
        require(context, OreDigTask.inspectCheckpoint(schemaThree, 8).isEmpty(),
                "schema3 open batch invented delivered=0");

        Map<String, String> legacyCommitted = new LinkedHashMap<>(legacyRunning);
        legacyCommitted.put("batch_open", "false");
        legacyCommitted.put("budget_used", "0");
        legacyCommitted.put("last_progress_budget", "0");
        OreDigTask.RestoreMetadata committed = OreDigTask.inspectCheckpoint(
                        legacyCommitted, 8)
                .orElseThrow();
        require(context, committed.torchPlacements() == 0 && committed.resourceEpoch() == 0,
                "schema1 committed cursor did not migrate to a clean successor epoch");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void rememberedHighWorkPoseCheckpointRejectsForgedEntries(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRememberedPoseCodecGT");
        BlockPos face = fixture.start();
        BlockPos ore = face.up(3);
        BlockPos pose = face.east().up(2);
        Map<String, String> valid = new LinkedHashMap<>(
                openCheckpoint(face, 1, Set.of(Blocks.COAL_ORE)));
        valid.put("remembered_high_work_poses", encode(ore) + "@" + encode(pose));
        require(context, OreDigTask.inspectCheckpoint(valid).isPresent(),
                "exact remembered high work pose failed codec validation");

        Map<String, String> duplicate = new LinkedHashMap<>(valid);
        duplicate.put("remembered_high_work_poses",
                encode(ore) + "@" + encode(pose) + ";"
                        + encode(ore) + "@" + encode(ore.down().west()));
        require(context, OreDigTask.inspectCheckpoint(duplicate).isEmpty(),
                "checkpoint accepted two work poses for one finite ore owner");

        Map<String, String> wrongGeometry = new LinkedHashMap<>(valid);
        wrongGeometry.put("remembered_high_work_poses",
                encode(ore) + "@" + encode(ore.down()));
        require(context, OreDigTask.inspectCheckpoint(wrongGeometry).isEmpty(),
                "checkpoint accepted an under-ore pose instead of a real side pose");

        BlockPos farOre = face.east(49).up(3);
        Map<String, String> far = new LinkedHashMap<>(valid);
        far.put("remembered_high_work_poses",
                encode(farOre) + "@" + encode(farOre.down().east()));
        require(context, OreDigTask.inspectCheckpoint(far).isEmpty(),
                "checkpoint accepted a remembered pose outside its bounded work region");

        Map<String, String> closed = new LinkedHashMap<>(valid);
        closed.put("batch_open", "false");
        require(context, OreDigTask.inspectCheckpoint(closed).isEmpty(),
                "committed checkpoint retained an unfinished observed-pose ledger");

        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index <= 64; index++) {
            BlockPos entryOre = face.add(index % 9 - 4, 3, index / 9 - 4);
            if (oversized.length() > 0) {
                oversized.append(';');
            }
            oversized.append(encode(entryOre))
                    .append('@')
                    .append(encode(entryOre.down().east()));
        }
        Map<String, String> tooMany = new LinkedHashMap<>(valid);
        tooMany.put("remembered_high_work_poses", oversized.toString());
        require(context, OreDigTask.inspectCheckpoint(tooMany).isEmpty(),
                "checkpoint accepted more than VEIN_CAP remembered poses");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void longRareTailRetainsMissionIdentityAcrossRestartAndServiceDebits(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreLongRareTailCheckpointGT");
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.STONE_PICKAXE));
        Map<String, String> tail = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 1, Set.of(Blocks.DIAMOND_ORE), 64));
        tail.put("direction", "2");
        tail.put("leg", "3");
        tail.put("steps_left", "17");
        tail.put("batches", "7");
        tail.put("boundary_reroute_origin", encode(fixture.start()));
        tail.put("torch_placements", "40");
        tail.put("budget_used", "123");
        tail.put("last_progress_budget", "100");
        tail.put("pending_pickup_pos", encode(fixture.start()));
        tail.put("pending_pickup_last_seen_pos", encode(fixture.start().east(2).down()));
        tail.put("pending_pickup_inventory", "5");
        tail.put("pending_pickup_started_budget", "90");
        tail.put("pickup_gain_budget", "110");
        String rememberedTailPose = encode(fixture.start().up(3))
                + "@" + encode(fixture.start().east().up(2));
        tail.put("remembered_high_work_poses", rememberedTailPose);

        OreDigTask.RestoreMetadata metadata = OreDigTask.inspectCheckpoint(tail, 64)
                .orElseThrow();
        require(context, metadata.targetCount() == 1 && metadata.rareMissionTarget() == 64,
                "long rare tail lost immutable mission identity: " + metadata);

        OreDigTask restarted = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 1, 64, tail);
        restarted.start(fixture.bot());
        Map<String, String> afterRestart = restarted.checkpoint();
        require(context, "1".equals(afterRestart.get("target_count"))
                        && "64".equals(afterRestart.get("rare_mission_target")),
                "ordinary restart converted a long rare tail into a small goal: " + afterRestart);
        assertCheckpointFieldsEqual(context, tail, afterRestart,
                "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_last_seen_pos", "pending_pickup_started_budget",
                "pickup_gain_budget", "remembered_high_work_poses");

        Map<String, String> epochOne = OreDigTask.advanceResourceEpoch(afterRestart)
                .orElseThrow();
        require(context, "0".equals(epochOne.get("torch_placements"))
                        && "1".equals(epochOne.get("resource_epoch"))
                        && "64".equals(epochOne.get("rare_mission_target")),
                "tail resource retry did not advance exactly one durable epoch: " + epochOne);
        assertCheckpointFieldsEqual(context, afterRestart, epochOne,
                "target_count", "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_last_seen_pos", "pending_pickup_started_budget",
                "pickup_gain_budget", "remembered_high_work_poses");

        Map<String, String> serviced = OreDigTask.debitInventoryService(epochOne)
                .orElseThrow();
        require(context, "true".equals(serviced.get("inventory_service_used"))
                        && OreDigTask.debitInventoryService(serviced).isEmpty(),
                "long rare tail accepted more than one inventory service debit");
        assertCheckpointFieldsEqual(context, epochOne, serviced,
                "target_count", "rare_mission_target", "torch_placements", "resource_epoch",
                "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_last_seen_pos", "pending_pickup_started_budget",
                "pickup_gain_budget", "remembered_high_work_poses");

        Map<String, String> ordinaryChannel = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 1, Set.of(Blocks.COAL_ORE), 0));
        ordinaryChannel.put("direction", "1");
        ordinaryChannel.put("steps_left", "23");
        ordinaryChannel.put("boundary_reroute_origin", encode(fixture.start()));
        ordinaryChannel.put("remembered_high_work_poses", rememberedTailPose);
        Map<String, String> channelDebited = OreDigTask
                .debitChannelToolResupply(ordinaryChannel)
                .orElseThrow();
        require(context, "true".equals(channelDebited.get("inventory_service_used"))
                        && encode(fixture.start()).equals(
                        channelDebited.get("boundary_reroute_origin"))
                        && OreDigTask.inspectCheckpoint(channelDebited, 0).isPresent()
                        && OreDigTask.debitChannelToolResupply(channelDebited).isEmpty(),
                "ordinary channel debit lost its reroute marker or accepted a second debit: "
                        + channelDebited);
        assertCheckpointFieldsEqual(context, ordinaryChannel, channelDebited,
                "target_count", "rare_mission_target", "torch_placements", "resource_epoch",
                "direction", "leg", "steps_left", "batches", "budget_used",
                "last_progress_budget", "boundary_reroute_origin",
                "remembered_high_work_poses");

        Map<String, String> smallRare = openCheckpoint(
                fixture.start(), 7, Set.of(Blocks.DIAMOND_ORE), 0);
        require(context, OreDigTask.advanceResourceEpoch(smallRare).isEmpty()
                        && OreDigTask.debitInventoryService(smallRare).isEmpty(),
                "small rare goal was misclassified as a long expedition batch");
        Map<String, String> capacityDebited = OreDigTask
                .debitCapacityHandoff(smallRare).orElseThrow();
        require(context, "true".equals(capacityDebited.get("inventory_service_used"))
                        && OreDigTask.debitCapacityHandoff(capacityDebited).isEmpty()
                        && OreDigTask.debitChannelToolResupply(capacityDebited).isEmpty(),
                "small rare capacity hand-off did not seal its first auxiliary debit");
        assertCheckpointFieldsEqual(context, smallRare, capacityDebited,
                "target_count", "rare_mission_target", "torch_placements", "resource_epoch",
                "origin", "face", "direction", "leg", "steps_left", "leg_length", "batches",
                "budget_used", "last_progress_budget", "ore_fingerprint");
        Map<String, String> progressedCapacity = new LinkedHashMap<>(capacityDebited);
        progressedCapacity.put("delivered", "1");
        Map<String, String> repeatedCapacity = OreDigTask
                .debitCapacityHandoff(progressedCapacity, 0).orElseThrow();
        require(context, repeatedCapacity.equals(progressedCapacity)
                        && OreDigTask.debitCapacityHandoff(progressedCapacity, 1).isEmpty()
                        && OreDigTask.debitCapacityHandoff(progressedCapacity, 2).isEmpty(),
                "capacity retry did not require a strict delivered-watermark advance");
        Map<String, String> advancedFaceCapacity = new LinkedHashMap<>(capacityDebited);
        advancedFaceCapacity.put("face", encode(fixture.start().east()));
        Map<String, String> faceRepeatedCapacity = OreDigTask.debitCapacityHandoff(
                advancedFaceCapacity, 0, fixture.start()).orElseThrow();
        require(context, faceRepeatedCapacity.equals(advancedFaceCapacity)
                        && OreDigTask.debitCapacityHandoff(
                        capacityDebited, 0, fixture.start()).isEmpty()
                        && OreDigTask.debitCapacityHandoff(
                        advancedFaceCapacity, 0, null).isEmpty(),
                "capacity retry did not require strict delivered or work-face progress");
        Map<String, String> invalidSmallMission = new LinkedHashMap<>(smallRare);
        invalidSmallMission.put("rare_mission_target", "7");
        require(context, OreDigTask.inspectCheckpoint(invalidSmallMission).isEmpty(),
                "schema3 accepted a non-zero rare mission target below eight");

        restarted.cancel(fixture.bot(), "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void rareFullInventoryFailsWithoutCreatingOpenRearDrops(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreInventoryServiceRequiredGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 40));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 64));
        while (!HarvestCore.isInventoryFull(bot)) {
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        }
        int dirtBefore = InventoryAction.countItem(bot, Items.DIRT);
        int cobbleBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        int openDropsBefore = world.getEntitiesByClass(
                ItemEntity.class, new Box(bot.getBlockPos()).expand(8.0D), entity -> true).size();
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 8));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 8, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "ore_dig_inventory_service_required".equals(task.failureReason()),
                "rare full inventory did not report its exact service boundary: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.DIRT) == dirtBefore
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == cobbleBefore,
                "OreDig disposed inventory before the sealed service task could run");
        int openDropsAfter = world.getEntitiesByClass(
                ItemEntity.class, new Box(bot.getBlockPos()).expand(8.0D), entity -> true).size();
        require(context, openDropsAfter == openDropsBefore,
                "OreDig created an open ItemEntity disposal path: before="
                        + openDropsBefore + ", after=" + openDropsAfter);
        require(context, "false".equals(task.checkpoint().get("inventory_service_used")),
                "OreDig debited inventory service before GoalExecutor scheduled it");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void ordinaryFullInventoryFailsWithoutMutatingInventoryOrCreatingDrops(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrdinaryInventoryServiceRequiredGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 40));
        while (!HarvestCore.isInventoryFull(bot)) {
            InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 64));
        }
        int dirtBefore = InventoryAction.countItem(bot, Items.DIRT);
        int cobbleBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        int dropsBefore = world.getEntitiesByClass(
                ItemEntity.class, new Box(bot.getBlockPos()).expand(8.0D), entity -> true).size();
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 1, Set.of(Blocks.IRON_ORE), 0));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.IRON_ORE), 1, 0, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "ore_dig_inventory_service_required".equals(task.failureReason()),
                "ordinary full inventory did not report the typed capacity boundary: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.DIRT) == dirtBefore
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == cobbleBefore,
                "ordinary OreDig mutated inventory before sealed service admission");
        int dropsAfter = world.getEntitiesByClass(
                ItemEntity.class, new Box(bot.getBlockPos()).expand(8.0D), entity -> true).size();
        require(context, dropsAfter == dropsBefore,
                "ordinary OreDig created an open ItemEntity disposal path: before="
                        + dropsBefore + ", after=" + dropsAfter);
        require(context, "false".equals(task.checkpoint().get("inventory_service_used")),
                "ordinary OreDig debited capacity service before GoalExecutor scheduled it");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void malformedCheckpointFailsClosed(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreMalformedCheckpointGT");
        Map<String, String> malformed = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        malformed.put("task_schema", "999");

        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, malformed);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "malformed OreDig checkpoint restarted as fresh work");
        require(context, "ore_dig_invalid_checkpoint".equals(task.failureReason()),
                "unexpected malformed-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "invalid restore published an invented successor checkpoint");

        Map<String, String> wrongStep = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8, Set.of(Blocks.DIAMOND_ORE), 64));
        wrongStep.put("delivered", "4");
        OreDigTask wrongCount = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 3, 64, wrongStep);
        wrongCount.start(fixture.bot());
        require(context, wrongCount.state() == TaskState.FAILED
                        && "ore_dig_invalid_checkpoint".equals(wrongCount.failureReason()),
                "open batch accepted a step count that was neither original 8 nor remainder 4");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void restartCannotResetHardBudget(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHardBudgetGT");
        Map<String, String> exhausted = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        exhausted.put("budget_used", "24000");
        exhausted.put("last_progress_budget", "24000");

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, exhausted);
        restored.start(fixture.bot());
        require(context, "24000".equals(restored.checkpoint().get("budget_used")),
                "restart reset the active OreDig hard budget");
        restored.tick(fixture.bot());

        require(context, restored.state() == TaskState.FAILED,
                "restored OreDig received a fresh hard-timeout window");
        require(context, restored.failureReason().startsWith("ore_dig_timeout"),
                "unexpected restored hard-budget failure: " + restored.failureReason());
        Map<String, String> terminal = restored.checkpoint();
        require(context, "true".equals(terminal.get("batch_open"))
                        && "24000".equals(terminal.get("budget_used")),
                "terminal hard timeout discarded its exhausted durable budget: " + terminal);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void restartCannotResetNoProgressBudget(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreNoProgressBudgetGT");
        Map<String, String> stalled = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        stalled.put("budget_used", "200");
        stalled.put("last_progress_budget", "0");

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, stalled);
        restored.start(fixture.bot());
        require(context, "200".equals(restored.checkpoint().get("budget_used")),
                "restart reset the active OreDig no-progress budget");
        restored.tick(fixture.bot());

        require(context, restored.state() == TaskState.FAILED,
                "restored OreDig received a fresh no-progress window");
        require(context, restored.failureReason().startsWith("ore_dig_no_progress"),
                "unexpected restored no-progress failure: " + restored.failureReason());
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 280)
    public void partialDeliveryRebasesOnlyTheTransientStallWindow(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePartialRetryBudgetGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos channel = fixture.start().north();
        world.setBlockState(channel, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        Map<String, String> nearlyExhausted = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 2, Set.of(Blocks.DIAMOND_ORE)));
        nearlyExhausted.put("budget_used", "23790");
        nearlyExhausted.put("last_progress_budget", "23790");
        nearlyExhausted.put("direction", "0");
        nearlyExhausted.put("steps_left", "12");

        AtomicReference<OreDigTask> active = new AtomicReference<>(
                new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 2, nearlyExhausted));
        TaskManager.INSTANCE.assign(bot, active.get(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_partial_retry_budget"));
        // Assignment starts the task and captures its inventory baseline. This item therefore
        // represents a real delivery made by the running attempt, not pre-existing inventory.
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND));
        int deathBaseline = deathCount(bot);
        AtomicBoolean restored = new AtomicBoolean();
        AtomicInteger restoredTicks = new AtomicInteger();

        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            OreDigTask task = active.get();
            if (!restored.get()) {
                if (task.state() == TaskState.CANCELLED) {
                    context.throwGameTestException("partial delivery task was cancelled");
                }
                if (task.state() != TaskState.FAILED) {
                    return;
                }
                require(context, task.failureReason().startsWith("ore_dig_no_progress"),
                        "fixture did not reach the intended stall boundary: " + task.failureReason());
                Map<String, String> successor = task.checkpoint();
                int budget = Integer.parseInt(successor.get("budget_used"));
                require(context, budget > 23990 && budget < 24000,
                        "fixture did not retain the nearly exhausted hard budget: " + successor);
                require(context, successor.get("last_progress_budget").equals(String.valueOf(budget)),
                        "partial delivery did not publish a fresh transient stall boundary: " + successor);
                require(context, "true".equals(successor.get("batch_open"))
                                && "2".equals(successor.get("target_count")),
                        "partial delivery committed or resized the original batch: " + successor);

                world.setBlockState(channel, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                OreDigTask retry = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, successor);
                TaskManager.INSTANCE.assign(bot, retry,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_ore_partial_retry_budget_restored"));
                active.set(retry);
                restored.set(true);
                return;
            }

            int ticks = restoredTicks.incrementAndGet();
            if (ticks == 1) {
                require(context, task.state() != TaskState.FAILED
                                || !task.failureReason().startsWith("ore_dig_no_progress"),
                        "successor attempt inherited an already-expired transient stall window");
            }
            if (task.state() == TaskState.FAILED) {
                require(context, task.failureReason().startsWith("ore_dig_timeout"),
                        "retry escaped the original hard budget or failed for the wrong reason: "
                                + task.failureReason());
                require(context, ticks <= 10,
                        "retry received a fresh hard-timeout budget: ticks=" + ticks);
                finish(context, fixture);
            } else if (task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("restored partial delivery task was cancelled");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 280)
    public void rarePartialDeliveryRestartOnlyMinesTheLogicalBatchRemainder(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRarePartialDeliveredGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos channel = fixture.start().north();
        world.setBlockState(channel, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);

        Map<String, String> open = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8, Set.of(Blocks.DIAMOND_ORE), 64));
        open.put("direction", "0");
        open.put("steps_left", "12");
        AtomicReference<OreDigTask> active = new AtomicReference<>(
                new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 8, 64, open));
        TaskManager.INSTANCE.assign(bot, active.get(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_rare_partial_delivery"));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 4));
        AtomicBoolean restarted = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            OreDigTask task = active.get();
            if (!restarted.get()) {
                if (task.state() == TaskState.CANCELLED) {
                    context.throwGameTestException("partial rare batch was cancelled");
                }
                if (task.state() != TaskState.FAILED) {
                    return;
                }
                require(context, task.failureReason().startsWith("ore_dig_no_progress"),
                        "partial rare fixture failed for the wrong reason: "
                                + task.failureReason());
                Map<String, String> successor = task.checkpoint();
                require(context, "4".equals(successor.get("delivered"))
                                && "8".equals(successor.get("target_count"))
                                && "true".equals(successor.get("batch_open")),
                        "partial rare failure lost its factual delivered ledger: " + successor);
                OreDigTask.RestoreMetadata metadata = OreDigTask.inspectCheckpoint(successor, 64)
                        .orElseThrow();
                require(context, metadata.remainingCount() == 4
                                && metadata.acceptsStepTarget(8)
                                && metadata.acceptsStepTarget(4),
                        "open batch did not expose replay/replan target identity: " + metadata);

                world.setBlockState(channel, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                // A process restart replays the persisted GoalStep(8). OreDig must still request
                // only the four items not already named by delivered=4.
                OreDigTask replay = new OreDigTask(
                        Set.of(Blocks.DIAMOND_ORE), 8, 64, successor);
                TaskManager.INSTANCE.assign(bot, replay,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_rare_partial_delivery_restarted"));
                active.set(replay);
                InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 4));
                restarted.set(true);
                return;
            }

            if (task.state() == TaskState.COMPLETED) {
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                        "restart mined more than the four-item remainder");
                Map<String, String> committed = task.checkpoint();
                require(context, "false".equals(committed.get("batch_open"))
                                && "0".equals(committed.get("delivered"))
                                && "1".equals(committed.get("batches")),
                        "remainder completion did not close exactly one logical batch: "
                                + committed);
                finish(context, fixture);
            } else if (task.state() == TaskState.FAILED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("rare remainder replay ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 30)
    public void restartAtFullyDeliveredOpenBatchSettlesDebtWithoutBreakingAnotherOre(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRareDeliveredGraceGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos untouched = fixture.start().north();
        bot.getServerWorld().setBlockState(
                untouched, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);

        Map<String, String> fullyDelivered = new LinkedHashMap<>(openCheckpoint(
                fixture.start(), 8, Set.of(Blocks.DIAMOND_ORE), 64));
        fullyDelivered.put("delivered", "8");
        fullyDelivered.put("budget_used", "24000");
        fullyDelivered.put("last_progress_budget", "24000");
        fullyDelivered.put("active_break_pos", encode(untouched));
        fullyDelivered.put("active_break_inventory", "8");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.DIAMOND_ORE), 8, 64, fullyDelivered);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.COMPLETED,
                "fully delivered restart required a tool or timed out: "
                        + restored.failureReason());
        require(context, bot.getServerWorld().getBlockState(untouched).isOf(Blocks.DIAMOND_ORE)
                        && InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                "fully delivered restart broke a ninth ore");
        Map<String, String> committed = restored.checkpoint();
        require(context, "false".equals(committed.get("batch_open"))
                        && "0".equals(committed.get("delivered"))
                        && "1".equals(committed.get("batches")),
                "fully delivered grace checkpoint did not commit exactly once: " + committed);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void completedBatchPublishesZeroBudgetSuccessor(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreCommittedBudgetGT");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1);
        task.start(fixture.bot());
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.DIAMOND));
        task.tick(fixture.bot());

        require(context, task.state() == TaskState.COMPLETED,
                "inventory-satisfied batch did not commit");
        Map<String, String> successor = task.checkpoint();
        require(context, "false".equals(successor.get("batch_open")),
                "completed batch remained open: " + successor);
        require(context, "0".equals(successor.get("budget_used"))
                        && "0".equals(successor.get("last_progress_budget")),
                "completed batch leaked its budget into the next batch: " + successor);
        require(context, "1".equals(successor.get("batches"))
                        && !successor.containsKey("pending_pickup_pos")
                        && !successor.containsKey("active_break_pos"),
                "completed successor retained active-batch debt: " + successor);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void higherTierNonTargetOreClosesBlindBranchWithoutBreakingIt(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTierObstacleGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        BlockPos gold = fixture.start().north().up();
        BlockPos east = fixture.start().east();
        bot.getServerWorld().setBlockState(
                gold, Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                east, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(
                east.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        int stoneDamageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "higher-tier non-target ore ended the branch: " + task.failureReason());
        require(context, bot.getServerWorld().getBlockState(gold).isOf(Blocks.GOLD_ORE),
                "stone-only iron search destroyed the finite gold obstruction");
        require(context, "48".equals(task.checkpoint().get("steps_left"))
                        && "1".equals(task.checkpoint().get("direction")),
                "higher-tier boundary did not preserve the unfinished leg through fresh east "
                        + "territory: " + task.checkpoint());
        int stoneDamageAfter = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();
        require(context, stoneDamageAfter == stoneDamageBefore,
                "reroute consumed stone-pick durability on unharvestable gold");

        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 240)
    public void progressedHigherTierBoundaryPublishesSuccessorAndSurvivesRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTierProgressedGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos progressed = start.east();
        BlockPos gold = progressed.east().up();
        BlockPos successor = progressed.south();
        BlockPos successorWork = successor.south();
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // EAST has already crossed one factual cell. Its NORTH/SOUTH neighbours are old open
        // corridors, while the preserved gold wall blocks the next EAST head cell. The normal
        // SOUTH successor first crosses old air, then has real stone work one cell farther on.
        for (BlockPos open : new BlockPos[]{start, progressed, progressed.north(), successor,
                progressed.east()}) {
            world.setBlockState(open, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(open.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(open.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(gold, Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(successorWork, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(successorWork.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(successorWork.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        assertStrictCapabilities(context, bot);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "1");
        checkpoint.put("leg", "1");
        checkpoint.put("steps_left", "31");
        checkpoint.put("leg_length", "48");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, progressed, "ore_tier_progressed_fixture"),
                "fixture could not publish its factual EAST advance");
        int damageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        task.tick(bot);
        Map<String, String> closed = task.checkpoint();
        int closedBudget = Integer.parseInt(closed.get("budget_used"));
        require(context, task.state() == TaskState.RUNNING
                        && bot.getBlockPos().equals(progressed)
                        && "2".equals(closed.get("direction"))
                        && "2".equals(closed.get("leg"))
                        && "96".equals(closed.get("steps_left"))
                        && "96".equals(closed.get("leg_length"))
                        && encode(progressed).equals(closed.get("face"))
                        && encode(progressed).equals(closed.get("boundary_reroute_origin"))
                        && encode(start).equals(closed.get("controlled_strip_rear"))
                        && OreDigTask.inspectCheckpoint(closed).isPresent(),
                "progressed tool boundary did not atomically publish its successor: " + closed);
        require(context, world.getBlockState(gold).isOf(Blocks.GOLD_ORE),
                "successor publication modified the finite gold obstruction");
        int damageAfterClose = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();
        require(context, damageAfterClose == damageBefore,
                "tool-boundary closure consumed pick durability");

        task.cancel(bot, "gametest_tool_boundary_restart");
        Map<String, String> restart = task.checkpoint();
        require(context, restart.equals(closed),
                "cancel changed the atomic tool-boundary checkpoint: " + restart);
        OreDigTask restored = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, restart);
        restored.start(bot);
        require(context, restored.state() == TaskState.RUNNING
                        && Integer.parseInt(restored.checkpoint().get("budget_used"))
                        == closedBudget,
                "restart rejected the tool-boundary successor or reset its hard budget");

        int healthBefore = Math.round(bot.getHealth());
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
            }
            require(context, restored.state() != TaskState.FAILED,
                    "restored tool-boundary successor failed: " + restored.failureReason());
            require(context, bot.isAlive() && Math.round(bot.getHealth()) == healthBefore
                            && !bot.getBlockPos().equals(progressed.east())
                            && world.getBlockState(gold).isOf(Blocks.GOLD_ORE),
                    "restored successor entered or modified the protected gold boundary");
            if (world.getBlockState(successorWork).isAir()
                    && world.getBlockState(successorWork.up()).isAir()) {
                Map<String, String> live = restored.checkpoint();
                int damageAfterWork = bot.getInventory().main.stream()
                        .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                        .mapToInt(ItemStack::getDamage)
                        .sum();
                require(context, Integer.parseInt(live.get("budget_used")) > closedBudget
                                && (bot.getBlockPos().equals(progressed)
                                || bot.getBlockPos().equals(successor))
                                && damageAfterWork == damageBefore + 2
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "restored successor did not physically clear exactly one body column: "
                                + live);
                restored.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 180) {
                context.throwGameTestException(
                        "tool-boundary successor never reached physical SOUTH work: "
                                + restored.checkpoint());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void zeroMovementHigherTierBoundaryStillFailsClosed(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTierZeroRearGT");
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos start = fixture.start();
        BlockPos gold = start.north().up();
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        for (BlockPos open : new BlockPos[]{start.north(), start.east(), start.west()}) {
            world.setBlockState(open, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(open.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(open.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(gold, Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        int damageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        OreDigTask task = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        Map<String, String> failed = task.checkpoint();
        int damageAfter = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "ore_dig_branch_boundary_trapped:tool_obstruction:")
                        && bot.getBlockPos().equals(start)
                        && world.getBlockState(gold).isOf(Blocks.GOLD_ORE)
                        && damageAfter == damageBefore
                        && "0".equals(failed.get("direction"))
                        && "48".equals(failed.get("steps_left"))
                        && OreDigTask.inspectCheckpoint(failed).isPresent(),
                "zero-movement tool boundary invented rear ownership: " + failed);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void stripPhysicallyRetreatsWhenGravityReoccupiesItsHead(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripGravelGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos buried = start.north();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // publish start as the previous factual branch face
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, buried, "ore_strip_gravel_fixture"),
                "fixture could not enter the next factual branch cell");
        context.getWorld().setBlockState(
                buried.up(), Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, !context.getWorld().getBlockState(buried.up())
                        .getCollisionShape(context.getWorld(), buried.up()).isEmpty(),
                "gravity fixture did not reoccupy the bot's head");
        float healthBefore = bot.getHealth();

        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "recoverable strip collapse ended OreDig: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "OreDig did not physically retreat to its previous face: "
                        + buried.toShortString() + " -> " + bot.getBlockPos().toShortString());
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "OreDig took damage before leaving the occupied body cell");
        require(context, context.getWorld().getBlockState(buried.up()).isOf(Blocks.GRAVEL),
                "strict retreat silently removed the gravity obstruction");
        require(context, "1".equals(task.checkpoint().get("steps_left"))
                        && "1".equals(task.checkpoint().get("direction"))
                        && encode(start).equals(
                        task.checkpoint().get("boundary_reroute_origin")),
                "collapsed north branch did not publish its finite visible escape: "
                        + task.checkpoint());

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void blockedBodyRetreatImmediatelyPublishesMarkerFreeRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRetreatCheckpointGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos safeRear = fixture.start();
        BlockPos blockedFace = safeRear.north();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, blockedFace, "ore_retreat_checkpoint_fixture"),
                "fixture could not enter the blocked branch face");
        context.getWorld().setBlockState(
                blockedFace.up(), Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                blockedFace, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        checkpoint.put("boundary_reroute_origin", encode(blockedFace));
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "fixture marker checkpoint did not decode");

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING && bot.getBlockPos().equals(safeRear),
                "blocked-body recovery did not synchronously reach its factual rear: "
                        + task.state() + ":" + task.failureReason());
        Map<String, String> immediate = task.checkpoint();
        require(context, OreDigTask.inspectCheckpoint(immediate).isPresent()
                        && encode(safeRear).equals(immediate.get("face"))
                        && "1".equals(immediate.get("steps_left"))
                        && "1".equals(immediate.get("direction"))
                        && encode(safeRear).equals(
                        immediate.get("boundary_reroute_origin")),
                "same-tick retreat checkpoint did not bind its finite factual escape: "
                        + immediate);

        task.cancel(bot, "gametest_immediate_checkpoint_restart");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, immediate);
        restored.start(bot);
        Map<String, String> restarted = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && OreDigTask.inspectCheckpoint(restarted).isPresent()
                        && encode(safeRear).equals(restarted.get("face"))
                        && encode(safeRear).equals(
                        restarted.get("boundary_reroute_origin")),
                "retreat restart lost or changed its bounded escape marker: " + restarted);

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 400)
    public void collapsedLateralDetourTriesRemainingFreshSideWithoutClosingLeg(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDetourCollapseGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos northBoundary = start.north();
        BlockPos westDetour = start.west();
        BlockPos southDetour = start.south();
        var world = context.getWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        world.setBlockState(
                northBoundary, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(
                westDetour, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(
                westDetour.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(
                southDetour, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(
                southDetour.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "39");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING
                        && "3".equals(task.checkpoint().get("direction"))
                        && encode(start).equals(
                        task.checkpoint().get("boundary_reroute_origin")),
                "initial gravity boundary did not select the fresh west detour: "
                        + task.checkpoint());

        world.setBlockState(westDetour, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(westDetour.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, westDetour, "ore_detour_collapse_fixture"),
                "fixture could not enter the factual west detour");
        world.setBlockState(
                westDetour.up(), Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        float healthBefore = bot.getHealth();

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING && bot.getBlockPos().equals(start),
                "collapsed west detour did not retreat to its factual origin: "
                        + task.state() + ":" + task.failureReason());
        Map<String, String> south = task.checkpoint();
        require(context, "2".equals(south.get("direction"))
                        && "39".equals(south.get("steps_left"))
                        && encode(start).equals(south.get("boundary_reroute_origin")),
                "collapse retreat did not immediately bind the remaining south branch: " + south);
        require(context, world.getBlockState(northBoundary).isOf(Blocks.GRAVEL)
                        && (world.getBlockState(westDetour).isOf(Blocks.GRAVEL)
                        || world.getBlockState(westDetour.up()).isOf(Blocks.GRAVEL)),
                "collapse reroute silently removed a gravity obstruction");

        task.cancel(bot, "gametest_collapse_restart");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, south);
        restored.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            restored.tick(bot);
            Map<String, String> live = restored.checkpoint();
            require(context, restored.state() != TaskState.FAILED,
                    "remaining fresh side failed after restart: " + restored.failureReason());
            require(context, !bot.getBlockPos().equals(northBoundary)
                            && !bot.getBlockPos().equals(westDetour),
                    "restored detour entered a rejected gravity branch");
            if (bot.getBlockPos().equals(southDetour)
                    && !live.containsKey("boundary_reroute_origin")) {
                // Movement is integrated after the task callback. The first callback that sees the
                // factual cell may have cleared the marker but not yet debited directional progress.
                if ("39".equals(live.get("steps_left"))) {
                    if (ticks.incrementAndGet() > 300) {
                        context.throwGameTestException(
                                "factual south move never consumed its finite cursor: " + live);
                    }
                    return;
                }
                require(context, "2".equals(live.get("direction"))
                                && Integer.parseInt(live.get("steps_left")) < 39,
                        "factual south move did not consume its finite cursor: " + live);
                require(context, world.getBlockState(northBoundary).isOf(Blocks.GRAVEL)
                                && (world.getBlockState(westDetour).isOf(Blocks.GRAVEL)
                                || world.getBlockState(westDetour.up()).isOf(Blocks.GRAVEL)),
                        "finite reroute mutated a protected gravity obstruction");
                require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                        "collapse recovery lost health before reaching the safe branch");
                restored.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 300) {
                context.throwGameTestException(
                        "restored south detour never published factual movement: " + live);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void pendingPickupGravityRetreatDoesNotBecomeBlindBranchTerminal(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OrePickupGravityOwnerGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos safeRear = fixture.start();
        BlockPos blockedFace = safeRear.north();
        BlockPos pendingDrop = safeRear.east(3);
        var world = context.getWorld();
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                safeRear, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        checkpoint.put("pending_pickup_pos", encode(pendingDrop));
        checkpoint.put("pending_pickup_inventory", "0");
        checkpoint.put("pending_pickup_started_budget", "0");
        require(context, OreDigTask.inspectCheckpoint(checkpoint).isPresent(),
                "pickup-owner gravity fixture checkpoint did not decode");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // restore the pickup owner and publish the factual branch rear
        require(context, encode(pendingDrop).equals(
                        task.checkpoint().get("pending_pickup_pos")),
                "fixture lost pickup ownership before the gravity collision");
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, blockedFace, "ore_pickup_gravity_owner_fixture"),
                "fixture could not enter the occupied forward cell");
        world.setBlockState(
                blockedFace.up(), Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos rejected : new BlockPos[]{
                safeRear.east(), safeRear.west(), safeRear.south()}) {
            world.setBlockState(rejected, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(rejected.up(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        Standability.clearCache();
        float healthBefore = bot.getHealth();

        task.tick(bot);

        Map<String, String> live = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING,
                "pickup-owned retreat was misclassified as a blind branch terminal: "
                        + task.failureReason());
        require(context, bot.getBlockPos().equals(safeRear),
                "pickup-owned gravity collision did not reach its factual safe rear");
        require(context, encode(pendingDrop).equals(live.get("pending_pickup_pos"))
                        && "0".equals(live.get("pending_pickup_inventory")),
                "gravity retreat discarded or rewrote the pending pickup ledger: " + live);
        require(context, "0".equals(live.get("direction"))
                        && "48".equals(live.get("steps_left"))
                        && !live.containsKey("boundary_reroute_origin"),
                "non-branch pickup owner mutated the strip cursor or reroute marker: " + live);
        require(context, world.getBlockState(blockedFace.up()).isOf(Blocks.GRAVEL),
                "pickup-owned retreat silently removed the gravity obstruction");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "pickup-owned retreat lost health before reaching safety");

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void stairDescentImmediatelyPublishesMarkerFreeRestartWithoutReverse(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreDescentCheckpointGT");
        AIPlayerEntity bot = fixture.bot();
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos landing = start.east().down();
        BlockPos ore = landing.down();
        world.setBlockState(landing, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos blockedApproach : new BlockPos[]{
                ore.down().north(), ore.down().east(),
                ore.down().south(), ore.down().west()}) {
            world.setBlockState(
                    blockedApproach, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        Standability.clearCache();
        require(context, Standability.isStandable(world, landing),
                "descent fixture did not expose a factual supported landing");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.DIAMOND_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        checkpoint.put("boundary_reroute_origin", encode(start));
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // lock the observed ore
        require(context, bot.getBlockPos().equals(start)
                        && encode(start).equals(
                        task.checkpoint().get("boundary_reroute_origin")),
                "fixture consumed the reroute marker before the synchronous descent");
        task.tick(bot); // digDownOneLayer -> descendInto(landing)

        require(context, task.state() == TaskState.RUNNING && bot.getBlockPos().equals(landing),
                "target stair did not synchronously reach its landing: "
                        + task.state() + ":" + task.failureReason());
        Map<String, String> immediate = task.checkpoint();
        require(context, OreDigTask.inspectCheckpoint(immediate).isPresent()
                        && encode(landing).equals(immediate.get("face"))
                        && "1".equals(immediate.get("direction"))
                        && "48".equals(immediate.get("steps_left"))
                        && !immediate.containsKey("boundary_reroute_origin"),
                "same-tick descent checkpoint retained stale cursor/reverse authorization: "
                        + immediate);

        task.cancel(bot, "gametest_immediate_checkpoint_restart");
        world.setBlockState(ore, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.east(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.north(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.south(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, immediate);
        restored.start(bot);
        Map<String, String> restarted = restored.checkpoint();
        require(context, OreDigTask.inspectCheckpoint(restarted).isPresent()
                        && encode(landing).equals(restarted.get("face"))
                        && !restarted.containsKey("boundary_reroute_origin"),
                "descent restart recreated the consumed reverse exception: " + restarted);
        restored.tick(bot);
        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith(
                        "ore_dig_branch_boundary_trapped:water:")
                        && bot.getBlockPos().equals(landing)
                        && world.getBlockState(start).isOf(Blocks.STONE),
                "marker-free restart authorized the sealed west reverse branch: "
                        + restored.state() + ":" + restored.failureReason()
                        + " checkpoint=" + restored.checkpoint());

        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void stairDescentSkipsUnsupportedPreferredDirection(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreSupportedDescentGT");
        AIPlayerEntity bot = fixture.bot();
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos unsupported = start.west().down();
        BlockPos supported = start.north().down();
        BlockPos ore = start.west().north().down(2);

        // The target's equal west/north delta makes west the deterministic preferred stair. Its
        // support is an open cave, while north is a factual dry landing. The task must select north
        // before opening the west body column; otherwise descendInto loops on no_landing and later
        // hands the self-created drop back to the blind-branch cursor.
        for (BlockPos body : new BlockPos[]{
                unsupported, unsupported.up(), unsupported.up(2), unsupported.down(),
                supported, supported.up(), supported.up(2)}) {
            world.setBlockState(body, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(supported.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos blockedApproach : new BlockPos[]{
                ore.down().north(), ore.down().east(),
                ore.down().south(), ore.down().west()}) {
            world.setBlockState(
                    blockedApproach, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        Standability.clearCache();
        require(context, !Standability.isStandable(world, unsupported)
                        && Standability.isStandable(world, supported),
                "fixture did not expose exactly one safe stair landing");
        require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "diagonal target ore is not strictly observable");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.DIAMOND_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // acquire the observed diagonal ore
        task.tick(bot); // choose north instead of the unsupported preferred west stair

        require(context, task.state() == TaskState.RUNNING && bot.getBlockPos().equals(supported),
                "stair approach did not choose its supported alternate: "
                        + task.state() + ":" + task.failureReason()
                        + " pos=" + bot.getBlockPos().toShortString());
        require(context, world.getBlockState(unsupported).isAir()
                        && world.getBlockState(unsupported.down()).isAir(),
                "stair approach opened or entered the unsupported preferred column");
        require(context, encode(supported).equals(task.checkpoint().get("face")),
                "supported descent did not publish its factual face: " + task.checkpoint());

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreTargetObservedLowerStepStrict", tickLimit = 40)
    public void targetApproachUsesOnlyObservedSupportedOneBlockLowerStep(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTargetLowerStepGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos landing = start.east().down();
        BlockPos ore = start.east(3).down();

        world.setBlockState(landing, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        // Keep every ordinary work pose unavailable so the target owner must exercise its
        // controlled one-cell approach instead of delegating the transition to PathExecutor.
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            world.setBlockState(
                    ore.down().offset(direction),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        Standability.clearCache();
        require(context, Standability.isStandable(world, landing)
                        && !Standability.isStandable(world, start.east())
                        && ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture did not expose one strict lower target stair");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.DIAMOND_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // acquire the observed finite ore
        task.tick(bot); // commit exactly one lower natural-cave stair

        Map<String, String> descended = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && bot.getBlockPos().equals(landing)
                        && encode(landing).equals(descended.get("face"))
                        && "17".equals(descended.get("steps_left"))
                        && OreDigTask.inspectCheckpoint(descended).isPresent(),
                "target lower stair did not preserve its exact cursor/budget: "
                        + task.state() + ":" + task.failureReason() + " " + descended);
        require(context, world.getBlockState(ore).isOf(Blocks.DIAMOND_ORE)
                        && bot.isOnGround(),
                "target lower stair broke the finite ore or published an unsupported pose");

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreTargetObservedLowerStepHazardStrict", tickLimit = 40)
    public void targetLowerStepRejectsObservedFluidNeighbourInStrictMode(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTargetLowerHazardGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos landing = start.east().down();
        BlockPos ore = start.east(3).down();
        BlockPos sideFluid = landing.north();

        world.setBlockState(landing, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sideFluid, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            world.setBlockState(
                    ore.down().offset(direction),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        Standability.clearCache();
        require(context, Standability.isStandable(world, landing)
                        && ObservableWorldQuery.canObserveBlock(bot, ore)
                        && (ObservableWorldQuery.canObserveCell(bot, sideFluid)
                        || ObservableWorldQuery.canObserveBlock(bot, sideFluid)),
                "fixture did not expose a strict lower landing with an observed fluid neighbour");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.DIAMOND_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        task.tick(bot);

        Map<String, String> rejected = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && bot.getBlockPos().equals(start)
                        && "17".equals(rejected.get("steps_left"))
                        && encode(start).equals(rejected.get("face"))
                        && world.getBlockState(sideFluid).isOf(Blocks.WATER),
                "strict lower approach entered or mutated an adjacent-fluid landing: "
                        + task.state() + ":" + task.failureReason() + " " + rejected);

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreTargetHiddenLowerStepHazardStrict", tickLimit = 20)
    public void lowerStepFluidGateRejectsUnobservableNeighbourInStrictMode(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTargetHiddenLowerHazardGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        int radius = Math.max(1, AIBotConfig.get().perception().radius());
        BlockPos hiddenCenter = fixture.start().east(radius + 8);
        BlockPos hiddenNeighbour = hiddenCenter.down();

        require(context, !ObservableWorldQuery.canObserveCell(bot, hiddenNeighbour)
                        && !ObservableWorldQuery.canObserveBlock(bot, hiddenNeighbour),
                "fixture neighbour was not outside strict-survival observation");
        require(context, !OreDigTask.isObservedAdjacentFluidSafe(
                        bot, context.getWorld(), hiddenCenter),
                "lower-step fluid gate accepted an unobservable neighbour");

        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreTargetProjectionRebaseStrict", tickLimit = 240)
    public void targetApproachMovementDoesNotSpendBlindBranchProjection(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTargetProjectionGT");
        AIPlayerEntity bot = fixture.bot();
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos ore = start.north(7);
        BlockPos lip = start.north(3);
        BlockPos drop = lip.north();

        world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(drop.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(drop.down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // Force controlled target tunnelling and leave one fresh east wall for the later blind
        // boundary reroute, so the test can inspect its remaining leg budget without terminating.
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            world.setBlockState(
                    ore.down().offset(direction),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(lip.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lip.east().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.DIAMOND_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "target projection fixture failed before reroute: "
                            + task.failureReason() + " " + task.checkpoint());
            Map<String, String> live = task.checkpoint();
            boolean targetExcluded = EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUuid(), ore, bot.getServer().getTicks());
            boolean rerouted = targetExcluded
                    && !"0".equals(live.get("direction"));
            if (rerouted) {
                require(context, bot.getBlockPos().equals(lip)
                                && "17".equals(live.get("steps_left"))
                                && encode(lip).equals(live.get("face"))
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "target movement was projected onto the blind cursor: " + live);
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 220) {
                context.throwGameTestException(
                        "target projection never reached its bounded reroute: "
                                + live + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreRichPathCursorOwnerStrict", tickLimit = 500)
    public void alignedRichZonePathDoesNotSpendBlindCursor(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRichCursorOwnerGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos firstForeignStep = start.north();
        BlockPos zone = start.north(20);
        for (int north = 0; north <= 22; north++) {
            for (int east = -2; east <= 2; east++) {
                BlockPos feet = start.north(north).east(east);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.clearFor(bot.getUuid());
        io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE.resetFor(bot.getUuid());
        for (BlockPos remembered : new BlockPos[]{zone, zone.east(10), zone.west(10)}) {
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(
                    bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND,
                    remembered,
                    "minecraft:emerald_ore");
        }
        require(context, io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                        .richZoneNear(bot.getUuid(), "minecraft:emerald_ore", start, 128, 3, 24)
                        .filter(zone::equals).isPresent(),
                "fixture did not publish the exact aligned rich-zone owner");

        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() == TaskState.RUNNING,
                    "rich-zone owner ended before its cursor assertion: "
                            + task.state() + ":" + task.failureReason());
            Map<String, String> live = task.checkpoint();
            require(context, "17".equals(live.get("steps_left")),
                    "foreign rich-zone path spent blind cursor budget: " + live);
            if (!bot.getActionPack().isPathExecutorIdle()
                    && bot.getBlockPos().equals(firstForeignStep)) {
                require(context, encode(firstForeignStep).equals(live.get("face"))
                                && !live.containsKey("controlled_strip_rear")
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "aligned foreign first step acquired blind ownership: " + live);
                io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.clearFor(bot.getUuid());
                io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE.resetFor(bot.getUuid());
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 440) {
                context.throwGameTestException(
                        "rich-zone path never exposed its aligned first step: "
                                + live + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBlindPendingOwnerStrict", tickLimit = 160)
    public void realBlindWalkerConsumesExactlyOneCursorStep(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreBlindPendingOwnerGT");
        AIPlayerEntity bot = fixture.bot();
        assertStrictCapabilities(context, bot);
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos first = start.north();
        BlockPos competingOre = start.east();
        int scanIntervalTicks = 10; // Mirrors OreDigTask.SCAN_INTERVAL for this regression window.
        bot.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOWNESS, 200, 5, false, false));
        require(context, bot.hasStatusEffect(StatusEffects.SLOWNESS),
                "fixture could not hold the blind walker at its rear");
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "17");
        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        AtomicInteger sawInFlightRearTicks = new AtomicInteger();
        AtomicBoolean competingOreInjected = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() == TaskState.RUNNING,
                    "blind owner ended before its first commit: "
                            + task.state() + ":" + task.failureReason());
            Map<String, String> live = task.checkpoint();
            int remaining = Integer.parseInt(live.get("steps_left"));
            require(context, remaining >= 16,
                    "one blind walker consumed more than one cursor step: " + live);
            if (bot.getBlockPos().equals(start)
                    && !bot.getActionPack().isWalkToIdle()) {
                int rearTicks = sawInFlightRearTicks.incrementAndGet();
                require(context, "17".equals(live.get("steps_left"))
                                && encode(start).equals(live.get("face")),
                        "in-flight rear published its blind cursor too early: " + live);
                if (competingOreInjected.compareAndSet(false, true)) {
                    world.setBlockState(
                            competingOre, Blocks.EMERALD_ORE.getDefaultState(), Block.NOTIFY_ALL);
                }
                if (rearTicks >= scanIntervalTicks + 1) {
                    bot.removeStatusEffect(StatusEffects.SLOWNESS);
                }
            }
            if (competingOreInjected.get() && remaining > 16) {
                require(context, !task.describe().contains(" ->"),
                        "in-flight blind owner admitted a competing ore target: "
                                + task.describe() + " " + live);
            }
            if (bot.getBlockPos().equals(first) && remaining == 16) {
                require(context, sawInFlightRearTicks.get() >= scanIntervalTicks + 1
                                && competingOreInjected.get()
                                && !task.describe().contains(" ->")
                                && encode(first).equals(live.get("face"))
                                && encode(start).equals(live.get("controlled_strip_rear"))
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "real blind walker did not exclusively publish exactly one owned edge: "
                                + task.describe() + " rear_ticks="
                                + sawInFlightRearTicks.get() + " " + live);
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 120) {
                context.throwGameTestException(
                        "real blind walker never committed its first owned step: "
                                + live + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchRaisedLandingStrict", tickLimit = 30)
    public void freshStripUsesUpperEscapeBeforeMiningUnsupportedLateralSupport(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRaisedLandingGT");
        AIPlayerEntity bot = fixture.bot();
        var world = context.getWorld();
        BlockPos start = fixture.start();
        BlockPos north = start.north();
        BlockPos east = start.east();
        BlockPos support = start.west();
        BlockPos raised = support.up();

        // Reproduce the seed-3000 descend handoff: the fresh north strip opens over a drop, east
        // is another unsupported open column, and west is the sole previous raised landing. Its
        // same-level stone block has air below and must remain intact as that landing's support.
        for (BlockPos open : new BlockPos[]{
                north, north.up(), north.down(),
                east, east.up(), east.down(),
                support.down(), raised, raised.up(), start.up(2)}) {
            world.setBlockState(open, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, !Standability.isStandable(world, north)
                        && !Standability.isStandable(world, east)
                        && Standability.isStandable(world, raised),
                "fixture did not expose exactly one raised escape landing");
        require(context, (ObservableWorldQuery.canObserveCell(bot, support)
                        || ObservableWorldQuery.canObserveBlock(bot, support))
                        && ObservableWorldQuery.canObserveCell(bot, raised)
                        && ObservableWorldQuery.canObserveCell(bot, raised.up())
                        && ObservableWorldQuery.canObserveCell(bot, start.up(2)),
                "raised escape fixture did not expose its complete support/body/sweep envelope");
        int ironDamageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.IRON_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        task.start(bot);
        task.tick(bot); // publish the fresh north leg
        require(context, "0".equals(task.checkpoint().get("direction"))
                        && "48".equals(task.checkpoint().get("steps_left")),
                "fresh OreDig did not publish its deterministic north leg: "
                        + task.checkpoint());
        int budgetBefore = Integer.parseInt(task.checkpoint().get("budget_used"));
        AtomicInteger boundaryTicks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "open-drop recovery failed before reaching the raised landing: "
                            + task.failureReason());
            require(context, world.getBlockState(support).isOf(Blocks.STONE)
                            && world.getBlockState(support.down()).isAir(),
                    "open-drop recovery mined or manufactured the raised landing support");
            int liveDamage = bot.getInventory().main.stream()
                    .filter(stack -> stack.isOf(Items.IRON_PICKAXE))
                    .mapToInt(ItemStack::getDamage)
                    .sum();
            require(context, liveDamage == ironDamageBefore,
                    "raised landing recovery consumed pick durability");
            if (!bot.getBlockPos().equals(raised)) {
                if (boundaryTicks.incrementAndGet() > 20) {
                    context.throwGameTestException(
                            "fresh strip never revisited its observed open-drop boundary: "
                                    + task.checkpoint());
                }
                return;
            }

            Map<String, String> raisedCheckpoint = new LinkedHashMap<>(task.checkpoint());
            int raisedBudget = Integer.parseInt(raisedCheckpoint.get("budget_used"));
            require(context, task.state() == TaskState.RUNNING && bot.isOnGround(),
                    "open-drop recovery did not publish a grounded raised landing");
            require(context, bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "raised landing recovery retained an active movement/mining channel");
            require(context, encode(raised).equals(raisedCheckpoint.get("face"))
                            && "0".equals(raisedCheckpoint.get("direction"))
                            && "1".equals(raisedCheckpoint.get("leg"))
                            && "48".equals(raisedCheckpoint.get("steps_left"))
                            && "48".equals(raisedCheckpoint.get("leg_length"))
                            && raisedBudget > budgetBefore
                            && encode(raised).equals(
                            raisedCheckpoint.get("boundary_reroute_origin"))
                            && !raisedCheckpoint.containsKey("controlled_strip_rear")
                            && OreDigTask.inspectCheckpoint(raisedCheckpoint).isPresent(),
                    "raised landing did not atomically publish its marked successor: "
                            + raisedCheckpoint);

            task.cancel(bot, "gametest_raised_landing_restart");
            OreDigTask restored = new OreDigTask(
                    Set.of(Blocks.COAL_ORE), 1, raisedCheckpoint);
            restored.start(bot);
            Map<String, String> successor = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && bot.getBlockPos().equals(raised)
                            && encode(raised).equals(successor.get("face"))
                            && "0".equals(successor.get("direction"))
                            && "1".equals(successor.get("leg"))
                            && "48".equals(successor.get("steps_left"))
                            && Integer.parseInt(successor.get("budget_used")) == raisedBudget
                            && encode(raised).equals(
                            successor.get("boundary_reroute_origin"))
                            && !successor.containsKey("controlled_strip_rear")
                            && successor.equals(raisedCheckpoint),
                    "raised landing restart changed its atomic bounded successor: "
                            + successor);
            require(context, world.getBlockState(support).isOf(Blocks.STONE)
                            && world.getBlockState(support.down()).isAir(),
                    "raised landing restart changed its natural support");

            restored.cancel(bot, "gametest_complete");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryStrict", tickLimit = 400)
    public void blindStripPersistsFreshLateralDetourAcrossCheckpoint(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripGravityGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos gravel = start.north();
        BlockPos east = start.east();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        context.getWorld().setBlockState(
                gravel, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                east, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                east.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        float healthBefore = bot.getHealth();

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "visible gravity reroute ended OreDig: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "blind strip entered the visible gravity column: "
                        + start.toShortString() + " -> " + bot.getBlockPos().toShortString());
        require(context, context.getWorld().getBlockState(gravel).isOf(Blocks.GRAVEL),
                "blind strip mined the finite gravity obstruction");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle(),
                "gravity reroute retained an active channel action");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "gravity reroute lost health before closing the branch");
        require(context, "48".equals(task.checkpoint().get("steps_left"))
                        && "1".equals(task.checkpoint().get("direction"))
                        && encode(start).equals(
                        task.checkpoint().get("boundary_reroute_origin")),
                "visible gravity boundary did not preserve its remaining east detour: "
                        + task.checkpoint());

        Map<String, String> rerouted = new LinkedHashMap<>(task.checkpoint());
        task.cancel(bot, "gametest_checkpoint_restart");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, rerouted);
        restored.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            restored.tick(bot);
            require(context, restored.state() != TaskState.FAILED,
                    "restored lateral detour failed: " + restored.failureReason());
            require(context, context.getWorld().getBlockState(gravel).isOf(Blocks.GRAVEL),
                    "restored detour consumed the rejected gravity boundary");
            require(context, bot.getBlockPos().getZ() == start.getZ(),
                    "restored detour entered forward/reverse old territory: "
                            + bot.getBlockPos().toShortString());
            require(context, "1".equals(restored.checkpoint().get("direction"))
                            && Integer.parseInt(restored.checkpoint().get("steps_left")) > 0,
                    "restored detour lost its east direction or remaining budget: "
                            + restored.checkpoint());
            if (bot.getBlockPos().getX() > start.getX()) {
                require(context, context.getWorld().getBlockState(east).isAir()
                                && context.getWorld().getBlockState(east.up()).isAir(),
                        "restored detour entered east before physically clearing its body column");
                // FakePlayer movement is integrated after this callback. Give OreDig one next
                // callback to publish the already-factual move into its durable remaining budget.
                if (Integer.parseInt(restored.checkpoint().get("steps_left")) == 48) {
                    return;
                }
                require(context,
                        !restored.checkpoint().containsKey("boundary_reroute_origin"),
                        "factual detour movement retained the one-origin reverse exception: "
                                + restored.checkpoint());
                restored.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 300) {
                context.throwGameTestException(
                        "restored detour did not physically open fresh east territory");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryTrappedStrict", tickLimit = 20)
    public void blindStripFailsFiniteWhenOnlyOldCorridorsRemain(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripBoundaryTrappedGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos gravel = start.north();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        context.getWorld().setBlockState(
                gravel, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "old-corridor-only boundary did not fail finitely: " + task.state());
        require(context, task.failureReason().startsWith(
                        "ore_dig_branch_boundary_trapped:gravity:"),
                "old-corridor-only boundary returned the wrong typed failure: "
                        + task.failureReason());
        require(context, bot.getBlockPos().equals(start)
                        && context.getWorld().getBlockState(gravel).isOf(Blocks.GRAVEL),
                "finite boundary failure moved the bot or consumed the obstruction");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchGravityProgressStrict", tickLimit = 500)
    public void progressedStripClosesVisibleGravityLegAndRestartsSuccessor(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripGravityProgressGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos progressedFace = start.north();
        BlockPos gravityFeet = progressedFace.north();
        BlockPos gravityHead = gravityFeet.up();
        BlockPos oldEastCorridor = progressedFace.east();
        BlockPos freshEastFace = progressedFace.east(2);
        var world = context.getWorld();

        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        world.setBlockState(gravityFeet, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(gravityHead, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(freshEastFace, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(freshEastFace.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, start)
                        && Standability.isStandable(world, progressedFace)
                        && Standability.isStandable(world, oldEastCorridor),
                "progressed-gravity fixture has the wrong dry corridor geometry");
        assertStrictCapabilities(context, bot);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");

        OreDigTask initial = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        initial.start(bot);
        AtomicReference<OreDigTask> liveTask = new AtomicReference<>(initial);
        AtomicBoolean closedGravityLeg = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger lastBudget = new AtomicInteger();
        AtomicInteger lastProgressBudget = new AtomicInteger();
        AtomicInteger closedBudget = new AtomicInteger(-1);
        AtomicInteger closedProgressBudget = new AtomicInteger(-1);
        AtomicInteger lastSuccessorSteps = new AtomicInteger(48);
        float healthBefore = bot.getHealth();
        int deathBaseline = deathCount(bot);

        context.runAtEveryTick(() -> {
            int callback = callbacks.incrementAndGet();
            OreDigTask task = liveTask.get();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "progressed gravity boundary ended OreDig: " + task.failureReason());
            require(context, bot.isAlive() && bot.getHealth() == healthBefore
                            && deathCount(bot) == deathBaseline,
                    "progressed gravity recovery lost health or a life");
            require(context, world.getBlockState(gravityFeet).isOf(Blocks.STONE)
                            && world.getBlockState(gravityHead).isOf(Blocks.GRAVEL),
                    "progressed gravity recovery consumed its protected obstruction");
            require(context, !bot.getBlockPos().equals(gravityFeet),
                    "progressed gravity recovery entered the rejected body column");

            Map<String, String> live = task.checkpoint();
            int budget = Integer.parseInt(live.get("budget_used"));
            int progressBudget = Integer.parseInt(live.get("last_progress_budget"));
            require(context, budget >= lastBudget.get()
                            && progressBudget >= lastProgressBudget.get()
                            && progressBudget <= budget,
                    "gravity boundary reset or forged a mining budget: " + live);
            lastBudget.set(budget);
            lastProgressBudget.set(progressBudget);

            if (!closedGravityLeg.get()) {
                if (!bot.getBlockPos().equals(progressedFace)
                        || !"1".equals(live.get("direction"))
                        || !"1".equals(live.get("leg"))) {
                    if (callback > 150) {
                        context.throwGameTestException(
                                "progressed branch never closed at its visible gravity boundary: "
                                        + live + " pos=" + bot.getBlockPos().toShortString());
                    }
                    return;
                }
                require(context, "48".equals(live.get("steps_left"))
                                && "48".equals(live.get("leg_length"))
                                && encode(progressedFace).equals(live.get("face"))
                                && encode(start).equals(live.get("controlled_strip_rear"))
                                && encode(progressedFace).equals(
                                live.get("boundary_reroute_origin"))
                                && progressBudget == budget
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "gravity leg closure did not atomically publish its factual successor: "
                                + live);
                require(context, bot.getActionPack().isPathExecutorIdle()
                                && bot.getActionPack().isWalkToIdle()
                                && bot.getActionPack().isMiningIdle(),
                        "gravity leg closure retained a movement/mining producer");

                closedBudget.set(budget);
                closedProgressBudget.set(progressBudget);
                task.cancel(bot, "gametest_gravity_leg_restart");
                OreDigTask restored = new OreDigTask(
                        Set.of(Blocks.EMERALD_ORE), 1, task.checkpoint());
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING
                                && encode(progressedFace).equals(
                                restored.checkpoint().get("face"))
                                && "1".equals(restored.checkpoint().get("direction"))
                                && "1".equals(restored.checkpoint().get("leg"))
                                && "48".equals(restored.checkpoint().get("steps_left"))
                                && encode(start).equals(restored.checkpoint().get(
                                "controlled_strip_rear"))
                                && encode(progressedFace).equals(restored.checkpoint().get(
                                "boundary_reroute_origin"))
                                && Integer.parseInt(
                                restored.checkpoint().get("budget_used")) == closedBudget.get()
                                && Integer.parseInt(restored.checkpoint().get(
                                "last_progress_budget")) == closedProgressBudget.get(),
                        "restart rejected or changed the closed gravity-leg checkpoint");
                liveTask.set(restored);
                closedGravityLeg.set(true);
                return;
            }

            int successorSteps = Integer.parseInt(live.get("steps_left"));
            require(context, "1".equals(live.get("direction"))
                            && successorSteps <= lastSuccessorSteps.get(),
                    "gravity successor changed direction or expanded its remaining budget: " + live);
            lastSuccessorSteps.set(successorSteps);
            require(context, bot.getBlockPos().getZ() == progressedFace.getZ()
                            && bot.getBlockPos().getX() >= progressedFace.getX()
                            && bot.getBlockPos().getX() <= freshEastFace.getX(),
                    "gravity successor left its bounded east corridor: "
                            + bot.getBlockPos().toShortString());
            if (!bot.getBlockPos().equals(freshEastFace)
                    || !encode(freshEastFace).equals(live.get("face"))
                    || Integer.parseInt(live.get("steps_left")) > 46) {
                if (callback > 450) {
                    context.throwGameTestException(
                            "gravity successor never opened and entered fresh east work: "
                                    + live + " pos=" + bot.getBlockPos().toShortString());
                }
                return;
            }

            require(context, world.getBlockState(freshEastFace).isAir()
                            && world.getBlockState(freshEastFace.up()).isAir()
                            && bot.isOnGround(),
                    "gravity successor entered fresh work without physically clearing it");
            task.cancel(bot, "gametest_complete");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchGravitySuccessorStrict", tickLimit = 500)
    public void gravityClosedLegRetainsRearAcrossImmediateGravitySuccessorAndRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripGravitySuccessorGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos progressedFace = start.west();
        BlockPos firstGravityFeet = progressedFace.west();
        BlockPos firstGravityHead = firstGravityFeet.up();
        BlockPos successorGravityFeet = progressedFace.north();
        BlockPos successorGravityHead = successorGravityFeet.up();
        var world = context.getWorld();

        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        world.setBlockState(firstGravityFeet, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(firstGravityHead, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(successorGravityFeet,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(successorGravityHead,
                Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, start)
                        && Standability.isStandable(world, progressedFace)
                        && Standability.isStandable(world, progressedFace.south()),
                "successive-gravity fixture has the wrong supported corridor geometry");
        assertStrictCapabilities(context, bot);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "3");
        checkpoint.put("leg", "4");
        checkpoint.put("steps_left", "144");
        checkpoint.put("leg_length", "144");

        OreDigTask initial = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        initial.start(bot);
        AtomicReference<OreDigTask> liveTask = new AtomicReference<>(initial);
        AtomicBoolean restoredAtSuccessor = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger lastBudget = new AtomicInteger();
        float healthBefore = bot.getHealth();
        int deathBaseline = deathCount(bot);

        context.runAtEveryTick(() -> {
            int callback = callbacks.incrementAndGet();
            OreDigTask task = liveTask.get();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "immediate gravity successor lost its factual rear: "
                            + task.failureReason() + " checkpoint=" + task.checkpoint());
            require(context, bot.isAlive() && bot.getHealth() == healthBefore
                            && deathCount(bot) == deathBaseline,
                    "successive gravity recovery lost health or a life");
            require(context, world.getBlockState(firstGravityFeet).isOf(Blocks.STONE)
                            && world.getBlockState(firstGravityHead).isOf(Blocks.GRAVEL)
                            && world.getBlockState(successorGravityFeet).isOf(Blocks.STONE)
                            && world.getBlockState(successorGravityHead).isOf(Blocks.GRAVEL),
                    "successive gravity recovery consumed a protected obstruction");
            require(context, !bot.getBlockPos().equals(firstGravityFeet)
                            && !bot.getBlockPos().equals(successorGravityFeet),
                    "successive gravity recovery entered a rejected body column");

            Map<String, String> live = task.checkpoint();
            int budget = Integer.parseInt(live.get("budget_used"));
            require(context, budget >= lastBudget.get(),
                    "successive gravity recovery reset its hard budget: " + live);
            lastBudget.set(budget);

            if (!restoredAtSuccessor.get()
                    && bot.getBlockPos().equals(progressedFace)
                    && "0".equals(live.get("direction"))
                    && "5".equals(live.get("leg"))) {
                require(context, "144".equals(live.get("steps_left"))
                                && "144".equals(live.get("leg_length"))
                                && encode(progressedFace).equals(live.get("face"))
                                && encode(start).equals(live.get("controlled_strip_rear"))
                                && encode(progressedFace).equals(
                                live.get("boundary_reroute_origin"))
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "gravity closure did not publish a restartable factual successor: "
                                + live);
                int restartBudget = budget;
                task.cancel(bot, "gametest_gravity_successor_restart");
                OreDigTask restored = new OreDigTask(
                        Set.of(Blocks.EMERALD_ORE), 1, task.checkpoint());
                restored.start(bot);
                Map<String, String> restoredCheckpoint = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING
                                && restoredCheckpoint.equals(live)
                                && Integer.parseInt(restoredCheckpoint.get(
                                "budget_used")) == restartBudget,
                        "restart changed the immediate gravity-successor transaction: "
                                + restoredCheckpoint);
                liveTask.set(restored);
                restoredAtSuccessor.set(true);
                return;
            }

            if (restoredAtSuccessor.get()
                    && bot.getBlockPos().equals(start)
                    && "2".equals(live.get("direction"))
                    && "6".equals(live.get("leg"))) {
                require(context, "192".equals(live.get("steps_left"))
                                && "192".equals(live.get("leg_length"))
                                && encode(start).equals(live.get("face"))
                                && encode(progressedFace).equals(
                                live.get("controlled_strip_rear"))
                                && encode(start).equals(
                                live.get("boundary_reroute_origin"))
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "bounded rear escape did not publish its factual spiral successor: "
                                + live);
                task.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }

            if (callback > 450) {
                context.throwGameTestException(
                        "successive gravity recovery never completed its bounded rear escape: "
                                + live + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryDelayedRearStrict", tickLimit = 500)
    public void minedOpenDropBodyRetainsFactualRearAcrossTicksAndRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripDelayedRearGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();
        BlockPos openDrop = lip.south();
        BlockPos westSupport = lip.west();
        BlockPos eastSupport = lip.east();
        var world = context.getWorld();

        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        world.setBlockState(openDrop, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(openDrop.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(openDrop.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(openDrop.down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos support : new BlockPos[]{westSupport, eastSupport}) {
            world.setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(support.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(support.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(support.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        // Both side walls look like possible raised landings, but the natural tunnel roof blocks
        // the takeoff sweep. They must be protected as UNSAFE rather than mined as fresh branches.
        world.setBlockState(lip.up(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, start)
                        && Standability.isStandable(world, lip)
                        && !Standability.isStandable(world, openDrop),
                "delayed open-drop fixture has the wrong initial support geometry");
        assertStrictCapabilities(context, bot);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask initial = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        initial.start(bot);
        AtomicReference<OreDigTask> liveTask = new AtomicReference<>(initial);
        AtomicBoolean reachedCommittedLip = new AtomicBoolean();
        AtomicBoolean restartedBetweenBodyBlocks = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger firstBreakCallback = new AtomicInteger(-1);
        AtomicInteger fullOpenCallback = new AtomicInteger(-1);
        AtomicInteger lastBudget = new AtomicInteger();
        int healthBefore = Math.round(bot.getHealth());
        int deathBaseline = deathCount(bot);
        int stonePickDamageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        context.runAtEveryTick(() -> {
            int callback = callbacks.incrementAndGet();
            OreDigTask task = liveTask.get();
            require(context, bot.getBlockPos().equals(start) || bot.getBlockPos().equals(lip),
                    "delayed open-drop fixture entered an unowned cell: "
                            + bot.getBlockPos().toShortString());
            require(context, bot.isAlive() && Math.round(bot.getHealth()) == healthBefore
                            && deathCount(bot) == deathBaseline,
                    "delayed open-drop recovery lost health or a life");
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "delayed open-drop recovery failed: " + task.failureReason());

            Map<String, String> live = task.checkpoint();
            int budget = Integer.parseInt(live.get("budget_used"));
            require(context, budget >= lastBudget.get(),
                    "delayed open-drop restart reset the hard budget: " + live);
            lastBudget.set(budget);
            if (bot.getBlockPos().equals(lip)
                    && encode(lip).equals(live.get("face"))
                    && "83".equals(live.get("steps_left"))) {
                reachedCommittedLip.set(true);
                require(context, encode(start).equals(live.get("controlled_strip_rear"))
                                && !live.containsKey("boundary_reroute_origin"),
                        "committed lip did not publish its exact controlled rear: " + live);
            }

            boolean feetOpen = world.getBlockState(openDrop).isAir();
            boolean headOpen = world.getBlockState(openDrop.up()).isAir();
            if (reachedCommittedLip.get() && firstBreakCallback.get() < 0
                    && (feetOpen || headOpen)) {
                firstBreakCallback.set(callback);
            }
            if (feetOpen && headOpen && fullOpenCallback.get() < 0) {
                fullOpenCallback.set(callback);
                require(context, firstBreakCallback.get() >= 0
                                && fullOpenCallback.get() > firstBreakCallback.get(),
                        "fixture did not mine feet/head across distinct production ticks");
            }

            // Restart after the first real block break, while the second body block still hides
            // the final open-drop classification. Production stages head before foot, and only
            // the persisted factual rear may survive across this mid-column boundary.
            if (!restartedBetweenBodyBlocks.get() && headOpen && !feetOpen) {
                require(context, reachedCommittedLip.get()
                                && encode(start).equals(live.get("controlled_strip_rear"))
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "mid-column checkpoint lost its controlled rear: " + live);
                int restartBudget = budget;
                task.cancel(bot, "gametest_delayed_rear_restart");
                Map<String, String> restart = task.checkpoint();
                require(context, encode(start).equals(restart.get("controlled_strip_rear"))
                                && Integer.parseInt(restart.get("budget_used")) == restartBudget,
                        "cancel boundary changed the factual rear or hard budget: " + restart);
                OreDigTask restored = new OreDigTask(
                        Set.of(Blocks.EMERALD_ORE), 1, restart);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING
                                && encode(start).equals(
                                restored.checkpoint().get("controlled_strip_rear")),
                        "restart rejected the exact controlled rear checkpoint");
                liveTask.set(restored);
                restartedBetweenBodyBlocks.set(true);
                return;
            }

            if (!reachedCommittedLip.get() || fullOpenCallback.get() < 0
                    || !bot.getBlockPos().equals(start)
                    || !"3".equals(live.get("direction"))
                    || !"4".equals(live.get("leg"))) {
                if (callback > 450) {
                    context.throwGameTestException(
                            "delayed open-drop branch never completed its rear retreat: "
                                    + live + " pos=" + bot.getBlockPos().toShortString());
                }
                return;
            }

            require(context, restartedBetweenBodyBlocks.get()
                            && "3".equals(live.get("direction"))
                            && "4".equals(live.get("leg"))
                            && "144".equals(live.get("steps_left"))
                            && "144".equals(live.get("leg_length"))
                            && encode(start).equals(live.get("face"))
                            && !live.containsKey("controlled_strip_rear")
                            && encode(start).equals(live.get("boundary_reroute_origin"))
                            && OreDigTask.inspectCheckpoint(live).isPresent(),
                    "rear retreat did not atomically publish its marked successor: " + live);
            require(context, world.getBlockState(openDrop).isAir()
                            && world.getBlockState(openDrop.up()).isAir()
                            && world.getBlockState(openDrop.down()).isAir()
                            && world.getBlockState(westSupport).isOf(Blocks.STONE)
                            && world.getBlockState(eastSupport).isOf(Blocks.STONE)
                            && world.getBlockState(westSupport.down()).isAir()
                            && world.getBlockState(eastSupport.down()).isAir(),
                    "rear retreat entered or modified a protected boundary candidate");
            int stonePickDamageAfter = bot.getInventory().main.stream()
                    .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                    .mapToInt(ItemStack::getDamage)
                    .sum();
            require(context, stonePickDamageAfter == stonePickDamageBefore + 2,
                    "fixture did not mine exactly the delayed feet/head blocks: before="
                            + stonePickDamageBefore + " after=" + stonePickDamageAfter);
            require(context, bot.isOnGround()
                            && bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "rear retreat retained a movement/mining producer");

            int retreatBudget = budget;
            task.cancel(bot, "gametest_delayed_rear_successor");
            OreDigTask successor = new OreDigTask(
                    Set.of(Blocks.EMERALD_ORE), 1, task.checkpoint());
            successor.start(bot);
            Map<String, String> advanced = successor.checkpoint();
            require(context, successor.state() == TaskState.RUNNING
                            && "3".equals(advanced.get("direction"))
                            && "4".equals(advanced.get("leg"))
                            && "144".equals(advanced.get("steps_left"))
                            && "144".equals(advanced.get("leg_length"))
                            && encode(start).equals(advanced.get("face"))
                            && encode(start).equals(
                            advanced.get("boundary_reroute_origin"))
                            && Integer.parseInt(advanced.get("budget_used"))
                            == retreatBudget,
                    "restart changed the atomic rear-retreat successor: "
                            + advanced);
            successor.cancel(bot, "gametest_complete");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryRearRetreatStrict", tickLimit = 400)
    public void progressedOpenDropLipRetreatsOneFactualStepAndRestartsSuccessor(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripRearRetreatGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();
        BlockPos openDrop = lip.south();
        var world = context.getWorld();

        // The live branch can advance one supported cell, then reaches an unsupported open body
        // column. Both lateral columns are already-open corridors, so they are not fresh mining
        // work; only the exact cell just vacated is a factual, bounded recovery landing.
        world.setBlockState(openDrop.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(openDrop.down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot); // publish the starting branch face and schedule its ordinary first step
        bot.getActionPack().stopAll();
        require(context, !Standability.isStandable(world, openDrop),
                "fixture did not create an unsupported forward body column");
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, lip, "ore_strip_rear_retreat_fixture"),
                "fixture could not complete its exact factual branch advance");
        AtomicInteger ticks = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean reachedLip =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        context.runAtEveryTick(() -> {
            require(context, bot.getBlockPos().equals(lip)
                            || bot.getBlockPos().equals(start),
                    "open-drop fixture entered an unowned cell: "
                            + bot.getBlockPos().toShortString());
            int budgetBefore = Integer.parseInt(task.checkpoint().get("budget_used"));
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "progressed open-drop recovery failed: " + task.failureReason());
            Map<String, String> retreated = task.checkpoint();
            if (reachedLip.get() && bot.getBlockPos().equals(start)
                    && "3".equals(retreated.get("direction"))
                    && "4".equals(retreated.get("leg"))) {
                int retreatBudget = Integer.parseInt(retreated.get("budget_used"));
                require(context, "3".equals(retreated.get("direction"))
                                && "4".equals(retreated.get("leg"))
                                && "144".equals(retreated.get("steps_left"))
                                && "144".equals(retreated.get("leg_length"))
                                && encode(start).equals(retreated.get("face"))
                                && encode(start).equals(
                                retreated.get("boundary_reroute_origin"))
                                && !retreated.containsKey("controlled_strip_rear")
                                && retreatBudget > budgetBefore,
                        "rear retreat did not atomically publish the bounded successor: "
                                + retreated);
                require(context, bot.getActionPack().isPathExecutorIdle()
                                && bot.getActionPack().isWalkToIdle()
                                && bot.getActionPack().isMiningIdle()
                                && world.getBlockState(start.down()).isOf(Blocks.STONE)
                                && world.getBlockState(openDrop.down()).isAir(),
                        "rear retreat retained movement or changed the protected lip geometry");

                task.cancel(bot, "gametest_rear_retreat_restart");
                OreDigTask restored = new OreDigTask(
                        Set.of(Blocks.EMERALD_ORE), 1, retreated);
                restored.start(bot);
                Map<String, String> successor = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING
                                && "3".equals(successor.get("direction"))
                                && "4".equals(successor.get("leg"))
                                && "144".equals(successor.get("steps_left"))
                                && "144".equals(successor.get("leg_length"))
                                && encode(start).equals(successor.get("face"))
                                && encode(start).equals(
                                successor.get("boundary_reroute_origin"))
                                && Integer.parseInt(successor.get("budget_used"))
                                == retreatBudget,
                        "restart changed the atomic bounded spiral successor: "
                                + successor);
                restored.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 300) {
                context.throwGameTestException(
                        "open-drop branch never completed its factual rear retreat: "
                                + task.checkpoint() + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryRearRetreatStrict", tickLimit = 400)
    public void rearRetreatKeepsTurnMarkerForImmediateSuccessorDrop(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreRetreatSuccessorDropGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();
        BlockPos firstDrop = lip.south();
        BlockPos successorDrop = start.west();
        BlockPos freshEast = start.east();
        var world = context.getWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // The SOUTH edge first advances to a supported lip and must retreat. Its normal WEST
        // successor is immediately another open drop. NORTH/SOUTH are old air corridors, while
        // EAST is fresh visible stone; only the retreat's same-origin marker authorizes checking
        // that third candidate instead of falsely terminating at the second lip.
        for (BlockPos drop : new BlockPos[]{firstDrop, successorDrop}) {
            world.setBlockState(drop, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(drop.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(drop.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(drop.down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(freshEast, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(freshEast.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(freshEast.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, lip, "ore_retreat_successor_drop_fixture"),
                "fixture could not complete its factual SOUTH advance");
        int stoneDamageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();
        AtomicReference<OreDigTask> liveTask = new AtomicReference<>(task);
        AtomicInteger stage = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger successorBudget = new AtomicInteger();
        AtomicInteger rerouteBudget = new AtomicInteger();
        context.runAtEveryTick(() -> {
            require(context, bot.getBlockPos().equals(lip)
                            || bot.getBlockPos().equals(start)
                            || bot.getBlockPos().equals(freshEast),
                    "successor-drop fixture entered an unowned cell: "
                            + bot.getBlockPos().toShortString());
            OreDigTask active = liveTask.get();
            if (active.state() == TaskState.RUNNING) {
                active.tick(bot);
            }
            require(context, active.state() != TaskState.FAILED,
                    "immediate successor drop lost the retreat turn: "
                            + active.failureReason());
            Map<String, String> live = active.checkpoint();
            if (stage.get() == 0
                    && bot.getBlockPos().equals(start)
                    && "3".equals(live.get("direction"))
                    && "4".equals(live.get("leg"))) {
                require(context, "144".equals(live.get("steps_left"))
                                && encode(start).equals(live.get("face"))
                                && encode(start).equals(
                                live.get("boundary_reroute_origin"))
                                && !live.containsKey("controlled_strip_rear")
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "retreat did not publish a restartable marked successor: " + live);
                successorBudget.set(Integer.parseInt(live.get("budget_used")));
                active.cancel(bot, "gametest_atomic_retreat_restart");
                Map<String, String> restart = active.checkpoint();
                require(context, restart.equals(live),
                        "cancel changed the marked successor checkpoint: " + restart);
                OreDigTask restored = new OreDigTask(
                        Set.of(Blocks.EMERALD_ORE), 1, restart);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING
                                && restored.checkpoint().equals(restart),
                        "restart rejected the immediate successor-drop boundary");
                liveTask.set(restored);
                stage.set(1);
                return;
            }
            if (stage.get() == 1 && "1".equals(live.get("direction"))) {
                require(context, bot.getBlockPos().equals(start)
                                && "4".equals(live.get("leg"))
                                && "144".equals(live.get("steps_left"))
                                && encode(start).equals(live.get("face"))
                                && encode(start).equals(
                                live.get("boundary_reroute_origin"))
                                && Integer.parseInt(live.get("budget_used"))
                                > successorBudget.get()
                                && world.getBlockState(firstDrop.down()).isAir()
                                && world.getBlockState(successorDrop.down()).isAir()
                                && world.getBlockState(freshEast).isOf(Blocks.STONE)
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "marked successor did not reroute to fresh EAST work: " + live);
                rerouteBudget.set(Integer.parseInt(live.get("budget_used")));
                stage.set(2);
                return;
            }
            if (stage.get() == 2 && world.getBlockState(freshEast).isAir()
                    && world.getBlockState(freshEast.up()).isAir()) {
                int damageAfter = bot.getInventory().main.stream()
                        .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                        .mapToInt(ItemStack::getDamage)
                        .sum();
                require(context, active.state() == TaskState.RUNNING
                                && Integer.parseInt(live.get("budget_used"))
                                > rerouteBudget.get()
                                && damageAfter == stoneDamageBefore + 2
                                && world.getBlockState(firstDrop.down()).isAir()
                                && world.getBlockState(successorDrop.down()).isAir()
                                && OreDigTask.inspectCheckpoint(live).isPresent(),
                        "restored successor did not physically open fresh EAST work: " + live);
                active.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 300) {
                context.throwGameTestException(
                        "rear retreat never recovered the immediate successor drop: " + live);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryUnsafeRearStrict", tickLimit = 80)
    public void progressedOpenDropWithUnsafeRearFailsWithoutMovingOrResettingBudget(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripUnsafeRearGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();
        BlockPos openDrop = lip.south();
        var world = context.getWorld();

        world.setBlockState(openDrop.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(openDrop.down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, lip, "ore_strip_unsafe_rear_fixture"),
                "fixture could not complete its factual branch advance");
        // The rear was factual when crossed, but the world changed before the boundary decision.
        // Recovery must revalidate it instead of trusting stale ownership.
        world.setBlockState(start.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, !Standability.isStandable(world, start),
                "fixture rear remained standable after support removal");

        // Hold the direct-walk owner open for one task tick. Even though the rear is no longer a
        // valid restart destination, the factual forward movement must be committed atomically
        // before the controller settles; a snapshot here may not mix lip face with 84 old steps.
        bot.getActionPack().startWalkTo(
                lip.toCenterPos(),
                io.github.zoyluo.aibot.action.WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);
        task.tick(bot);
        Map<String, String> delayed = task.checkpoint();
        int delayedBudget = Integer.parseInt(delayed.get("budget_used"));
        require(context, task.state() == TaskState.RUNNING
                        && encode(lip).equals(delayed.get("face"))
                        && "83".equals(delayed.get("steps_left"))
                        && !delayed.containsKey("boundary_reroute_origin")
                        && OreDigTask.inspectCheckpoint(delayed).isPresent(),
                "unsafe rear snapshot mixed its factual face and branch cursor: " + delayed);

        task.cancel(bot, "gametest_unsafe_rear_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.EMERALD_ORE), 1, delayed);
        restored.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
            }
            require(context, bot.getBlockPos().equals(lip),
                    "unsafe rear recovery moved before proving its landing: "
                            + bot.getBlockPos().toShortString());
            if (restored.state() == TaskState.FAILED) {
                Map<String, String> failed = restored.checkpoint();
                int failedBudget = Integer.parseInt(failed.get("budget_used"));
                require(context, restored.failureReason().startsWith(
                                "ore_dig_branch_boundary_trapped:open_drop:")
                                && OreDigTask.inspectCheckpoint(failed).isPresent()
                                && encode(lip).equals(failed.get("face"))
                                && "2".equals(failed.get("direction"))
                                && "3".equals(failed.get("leg"))
                                && "83".equals(failed.get("steps_left"))
                                && !failed.containsKey("boundary_reroute_origin")
                                && failedBudget > delayedBudget
                                && world.getBlockState(start.down()).isAir()
                                && world.getBlockState(openDrop.down()).isAir(),
                        "unsafe rear restart changed its committed geometry/cursor: " + failed);

                OreDigTask retriedTask = new OreDigTask(
                        Set.of(Blocks.EMERALD_ORE), 1, failed);
                retriedTask.start(bot);
                retriedTask.tick(bot);
                Map<String, String> retried = retriedTask.checkpoint();
                require(context, retriedTask.state() == TaskState.FAILED
                                && retriedTask.failureReason().equals(
                                restored.failureReason())
                                && bot.getBlockPos().equals(lip)
                                && "83".equals(retried.get("steps_left"))
                                && Integer.parseInt(retried.get("budget_used"))
                                == failedBudget + 1,
                        "unsafe rear restart changed the typed result or reset hard budget: "
                                + retried);
                finish(context, fixture);
                return;
            }
            require(context, restored.state() == TaskState.RUNNING,
                    "unsafe rear ended with the wrong terminal state: "
                            + restored.state());
            if (ticks.incrementAndGet() > 40) {
                context.throwGameTestException(
                        "unsafe rear did not fail within its bounded scan window: "
                                + restored.checkpoint());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchScanDelayRestartStrict", tickLimit = 240)
    public void scanDelayCheckpointRestoresSafeRearBeforeReplayingBranch(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripDelayRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask first = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        first.start(bot);
        first.tick(bot);
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, lip, "ore_strip_scan_delay_fixture"),
                "fixture could not complete its exact factual branch advance");
        // Keep the direct-walk owner live for this same task tick. The task must not consume the
        // cursor or discover a new owner until that controller settles.
        bot.getActionPack().startWalkTo(
                lip.toCenterPos(),
                io.github.zoyluo.aibot.action.WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);
        first.tick(bot);
        Map<String, String> delayed = first.checkpoint();
        int delayedBudget = Integer.parseInt(delayed.get("budget_used"));
        require(context, first.state() == TaskState.RUNNING
                        && bot.getBlockPos().equals(lip)
                        && encode(start).equals(delayed.get("face"))
                        && "84".equals(delayed.get("steps_left"))
                        && encode(start).equals(delayed.get("boundary_reroute_origin"))
                        && OreDigTask.inspectCheckpoint(delayed).isPresent(),
                "scan-delay checkpoint mixed pre/post-move cursor state: " + delayed);

        first.cancel(bot, "gametest_scan_delay_restart");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, delayed);
        restored.start(bot);
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
            }
            require(context, restored.state() != TaskState.FAILED,
                    "scan-delay restart failed before restoring its rear: "
                            + restored.failureReason());
            Map<String, String> live = restored.checkpoint();
            require(context, Integer.parseInt(live.get("budget_used")) >= delayedBudget,
                    "scan-delay restart reset its hard budget: " + live);
            if (bot.getBlockPos().equals(start)
                    && "84".equals(live.get("steps_left"))) {
                require(context, encode(start).equals(live.get("face"))
                                && encode(start).equals(
                                live.get("boundary_reroute_origin"))
                                && bot.getActionPack().isPathExecutorIdle(),
                        "restart reached rear without restoring the full pre-move cursor: " + live);
                restored.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 180) {
                context.throwGameTestException(
                        "scan-delay restart never returned to its safe factual rear: "
                                + live + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchPauseResumeStrict", tickLimit = 260)
    public void survivalGuardPauseDisplacementRestoresUnpublishedRearAndCursor(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripPauseResumeGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos lip = start.south();
        BlockPos safetyFace = lip.east();

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.EMERALD_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");
        checkpoint.put("boundary_reroute_origin", encode(start));

        OreDigTask task = new OreDigTask(Set.of(Blocks.EMERALD_ORE), 1, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_branch_pause_resume"));
        TaskManager.INSTANCE.tickAll(context.getWorld().getServer());
        bot.getActionPack().stopAll();
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, lip, "ore_strip_pause_resume_fixture"),
                "fixture could not complete its unpublished branch advance");
        bot.getActionPack().startWalkTo(
                lip.toCenterPos(),
                io.github.zoyluo.aibot.action.WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);

        Map<String, String> beforeGuard = task.checkpoint();
        int budgetBeforeGuard = Integer.parseInt(beforeGuard.get("budget_used"));
        bot.setHealth(5.0F);
        bot.hurtTime = 5;
        TaskManager.INSTANCE.tickAll(context.getWorld().getServer());

        Map<String, String> paused = task.checkpoint();
        require(context, task.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == task
                        && encode(start).equals(paused.get("face"))
                        && "84".equals(paused.get("steps_left"))
                        && encode(start).equals(paused.get("boundary_reroute_origin"))
                        && Integer.parseInt(paused.get("budget_used")) == budgetBeforeGuard,
                "survival guard consumed or rewrote the unpublished branch: " + paused);

        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, safetyFace, "ore_strip_pause_safety_displacement"),
                "fixture safety task could not displace the paused miner");
        bot.setHealth(bot.getMaxHealth());
        bot.hurtTime = 0;
        TaskManager.INSTANCE.resumeFromPause(bot);

        Map<String, String> resumed = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                        && encode(start).equals(resumed.get("face"))
                        && "84".equals(resumed.get("steps_left"))
                        && encode(start).equals(resumed.get("boundary_reroute_origin")),
                "resume adopted the safety task destination as mining progress: " + resumed);

        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            require(context, task.state() != TaskState.FAILED
                            && task.state() != TaskState.CANCELLED,
                    "paused branch failed while restoring its factual rear: "
                            + task.state() + ":" + task.failureReason());
            Map<String, String> live = task.checkpoint();
            require(context, OreDigTask.inspectCheckpoint(live).isPresent()
                            && encode(start).equals(live.get("face"))
                            && "84".equals(live.get("steps_left"))
                            && encode(start).equals(live.get("boundary_reroute_origin"))
                            && Integer.parseInt(live.get("budget_used"))
                            >= budgetBeforeGuard,
                    "safety displacement mutated the pre-move cursor: " + live);
            if (bot.getBlockPos().equals(start)
                    && bot.getActionPack().isPathExecutorIdle()
                    && !task.describe().contains("returning to saved face")) {
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 220) {
                context.throwGameTestException(
                        "resumed branch never restored its exact factual rear: checkpoint="
                                + live + " pos=" + bot.getBlockPos().toShortString());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryCascadeStrict", tickLimit = 400)
    public void sameOriginWaterThenLavaTriesUnvisitedReverseAndSurvivesRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripBoundaryCascadeGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        var world = context.getWorld();
        BlockPos east = start.east();
        BlockPos south = start.south();
        BlockPos north = start.north();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // First boundary: east is visibly wet. South and north are both factual fresh body
        // columns, so deterministic clockwise priority initially selects south.
        world.setBlockState(east, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(south, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(south.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(north, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(north.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "1");
        checkpoint.put("steps_left", "48");

        OreDigTask first = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        first.start(bot);
        first.tick(bot);
        require(context, first.state() == TaskState.RUNNING,
                "water boundary terminated the first reroute: " + first.failureReason());
        Map<String, String> southReroute = first.checkpoint();
        require(context, "2".equals(southReroute.get("direction"))
                        && "48".equals(southReroute.get("steps_left"))
                        && encode(start).equals(southReroute.get("face"))
                        && encode(start).equals(
                        southReroute.get("boundary_reroute_origin")),
                "first same-origin boundary did not persist the south detour: " + southReroute);
        require(context, OreDigTask.inspectCheckpoint(southReroute).isPresent(),
                "first same-origin reroute checkpoint did not decode: " + southReroute);

        // Restart before the second observation. Lava now occupies the selected south body cell
        // and rejects it without any factual movement from start. East remains wet,
        // west is the old open corridor, and north is the sole unvisited safe exit.
        first.cancel(bot, "gametest_boundary_restart");
        world.setBlockState(south, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, southReroute);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "second same-origin lava boundary ignored the safe north exit: "
                        + restored.failureReason());
        Map<String, String> northReroute = restored.checkpoint();
        require(context, "0".equals(northReroute.get("direction"))
                        && "48".equals(northReroute.get("steps_left"))
                        && encode(start).equals(northReroute.get("face"))
                        && encode(start).equals(
                        northReroute.get("boundary_reroute_origin")),
                "restored cascade did not retain the unvisited north exit: " + northReroute);
        require(context, world.getBlockState(east).isOf(Blocks.WATER)
                        && world.getBlockState(south).isOf(Blocks.LAVA),
                "boundary traversal modified a protected fluid instead of rerouting");
        // The source has served its immediate observation contract. Replace it before yielding to
        // the global DangerWatcher, whose independent safety task would otherwise take ownership
        // of this deliberately adjacent test hazard and obscure OreDig's persisted reroute.
        world.setBlockState(south, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
            }
            require(context, restored.state() != TaskState.FAILED,
                    "safe north exit failed after cascade reroute: " + restored.failureReason());
            // The behavioral contract is that OreDig never enters either rejected branch or the
            // remaining water source after the immediate lava no-mutation assertion above.
            require(context, !bot.getBlockPos().equals(east)
                            && !bot.getBlockPos().equals(south)
                            && !bot.isSubmergedInWater()
                            && !bot.isInLava(),
                    "safe exit entered a rejected fluid branch");
            if (bot.getBlockPos().equals(north)) {
                require(context, world.getBlockState(north).isAir()
                                && world.getBlockState(north.up()).isAir(),
                        "miner entered north before physically clearing the safe body column");
                restored.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 300) {
                context.throwGameTestException(
                        "cascade reroute selected north but never opened its safe exit: "
                                + restored.checkpoint());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryBacktrackStrict", tickLimit = 400)
    public void sameOriginFluidCascadeBacktracksOneObservedStepAndRestarts(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripBoundaryBacktrackGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        var world = context.getWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // South is the original blocked leg. West is initially the only fresh detour, east is
        // visibly wet, and north is the already controlled two-high rear corridor.
        world.setBlockState(start.south(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.west().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "2");
        checkpoint.put("leg", "3");
        checkpoint.put("steps_left", "84");
        checkpoint.put("leg_length", "96");

        OreDigTask first = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        first.start(bot);
        first.tick(bot);
        Map<String, String> westReroute = first.checkpoint();
        require(context, first.state() == TaskState.RUNNING
                        && "3".equals(westReroute.get("direction"))
                        && "84".equals(westReroute.get("steps_left"))
                        && encode(start).equals(westReroute.get("boundary_reroute_origin")),
                "first fluid boundary did not publish the fresh west detour: " + westReroute);

        // Restart before movement and place lava in the selected west body. No fresh solid
        // candidate remains, but north is fully observed, dry, standable and already controlled.
        first.cancel(bot, "gametest_backtrack_restart");
        world.setBlockState(start.west(), Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, westReroute);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> backtrack = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && "0".equals(backtrack.get("direction"))
                        && "1".equals(backtrack.get("steps_left"))
                        && encode(start).equals(backtrack.get("boundary_reroute_origin")),
                "same-origin fluid cascade did not publish one-step rear escape: " + backtrack);
        require(context, OreDigTask.inspectCheckpoint(backtrack).isPresent(),
                "one-step rear escape checkpoint did not decode: " + backtrack);
        require(context, world.getBlockState(start.west()).isOf(Blocks.LAVA),
                "backtrack observation mutated the rejected lava body");
        // The source has served its immediate observation contract. Seal it before yielding to the
        // global DangerWatcher so this fixture continues to exercise OreDig's one-step owner.
        world.setBlockState(start.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        TaskManager.INSTANCE.assign(bot, restored,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_same_origin_fluid_backtrack"));

        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            require(context, restored.state() != TaskState.FAILED,
                    "observed rear escape failed: " + restored.failureReason());
            require(context, !bot.isSubmergedInWater() && !bot.isInLava(),
                    "rear escape entered a rejected fluid branch");
            Map<String, String> live = restored.checkpoint();
            if (bot.getBlockPos().equals(start.north())
                    && encode(start.north()).equals(
                    live.get("boundary_reroute_origin"))
                    && "1".equals(live.get("direction"))
                    && "4".equals(live.get("leg"))) {
                require(context, "144".equals(live.get("steps_left"))
                                && encode(start.north()).equals(live.get("face"))
                                && encode(start).equals(live.get("controlled_strip_rear"))
                                && live.get("budget_used").equals(
                                live.get("last_progress_budget"))
                                && OreDigTask.inspectCheckpoint(live).isPresent()
                                && world.getBlockState(start.south()).isOf(Blocks.WATER)
                                && world.getBlockState(start.east()).isOf(Blocks.WATER)
                                && world.getBlockState(start.west()).isOf(Blocks.STONE),
                        "rear escape did not publish its factual successor without mutating fluids: "
                                + live);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_backtrack_second_restart");
                int successorBudget = Integer.parseInt(live.get("budget_used"));
                int successorProgress = Integer.parseInt(live.get("last_progress_budget"));
                OreDigTask resumed = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, live);
                resumed.start(bot);
                Map<String, String> resumedCheckpoint = resumed.checkpoint();
                require(context, OreDigTask.inspectCheckpoint(resumedCheckpoint).isPresent()
                                && "1".equals(resumedCheckpoint.get("direction"))
                                && "4".equals(resumedCheckpoint.get("leg"))
                                && "144".equals(resumedCheckpoint.get("steps_left"))
                                && encode(start.north()).equals(resumedCheckpoint.get("face"))
                                && encode(start.north()).equals(
                                resumedCheckpoint.get("boundary_reroute_origin"))
                                && encode(start).equals(
                                resumedCheckpoint.get("controlled_strip_rear"))
                                && resumedCheckpoint.equals(live),
                        "post-escape restart lost the new branch cursor: " + resumedCheckpoint);

                // Freeze the production failure shape at this factual face: its EAST successor is
                // immediately wet, SOUTH is the crossed rear, NORTH is unbreakable, and WEST is the
                // sole fresh solid candidate. The factual-corner pair must survive restart long
                // enough to select WEST without moving, resetting budget, or mutating the fluid.
                BlockPos factualFace = start.north();
                BlockPos immediateFluid = factualFace.east();
                BlockPos unbreakableNorth = factualFace.north();
                BlockPos freshWest = factualFace.west();
                world.setBlockState(
                        immediateFluid, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(
                        unbreakableNorth, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(
                        unbreakableNorth.up(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(
                        freshWest, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(
                        freshWest.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                resumed.tick(bot);
                Map<String, String> rerouted = resumed.checkpoint();
                require(context, resumed.state() == TaskState.RUNNING
                                && bot.getBlockPos().equals(factualFace)
                                && "3".equals(rerouted.get("direction"))
                                && "4".equals(rerouted.get("leg"))
                                && "144".equals(rerouted.get("steps_left"))
                                && encode(factualFace).equals(rerouted.get("face"))
                                && encode(factualFace).equals(
                                rerouted.get("boundary_reroute_origin"))
                                && !rerouted.containsKey("controlled_strip_rear")
                                && Integer.parseInt(rerouted.get("budget_used"))
                                == successorBudget + 1
                                && Integer.parseInt(rerouted.get("last_progress_budget"))
                                == successorProgress
                                && world.getBlockState(immediateFluid).isOf(Blocks.WATER)
                                && world.getBlockState(freshWest).isOf(Blocks.STONE)
                                && OreDigTask.inspectCheckpoint(rerouted).isPresent(),
                        "restored factual escape did not reroute the immediate successor boundary: "
                                + rerouted);
                resumed.cancel(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 300) {
                context.throwGameTestException(
                        "one-step rear escape never published its successor branch: " + live);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreHiddenLowerTransitionStrict", tickLimit = 20)
    public void hiddenLowerTransitionRemainsUnknownWhetherBlockedOrOpen(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHiddenTransitionGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos transition = fixture.start().north(80).up();
        var world = context.getWorld();

        world.setBlockState(transition, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        OreScan.Observation hiddenStone = OreDigTask.observePickupEgressClearance(
                bot, world, transition);
        world.setBlockState(transition, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        OreScan.Observation hiddenAir = OreDigTask.observePickupEgressClearance(
                bot, world, transition);
        require(context, hiddenStone == OreScan.Observation.UNKNOWN
                        && hiddenAir == OreScan.Observation.UNKNOWN,
                "hidden lower transition leaked blocked/open state: stone="
                        + hiddenStone + " air=" + hiddenAir);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchHiddenFluidParityStrict", tickLimit = 40)
    public void hiddenSideFluidAndHiddenStoneOpenTheSameSealedChannel(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHiddenFluidParityGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos forward = start.north();
        BlockPos hidden = forward.east();
        var world = context.getWorld();

        world.setBlockState(forward, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(forward.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // Enclose the lateral candidate completely. OreDig may open the visible forward wall, but
        // must not inspect the candidate to choose a different first action.
        for (BlockPos enclosure : new BlockPos[]{
                start.east(), hidden.east(), hidden.north(), hidden.up(), hidden.down()}) {
            world.setBlockState(enclosure, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(hidden, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, OreScan.observeDangerFluid(bot, hidden)
                        == OreScan.Observation.UNKNOWN,
                "stone candidate was not hidden behind the sealed channel envelope");

        Map<String, String> initial = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        initial.put("direction", "0");
        initial.put("steps_left", "12");
        OreDigTask stoneTask = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, initial);
        stoneTask.start(bot);
        stoneTask.tick(bot);
        Map<String, String> stoneResult = new LinkedHashMap<>(stoneTask.checkpoint());
        boolean stoneOpenedChannel = !bot.getActionPack().isMiningIdle();
        stoneTask.cancel(bot, "gametest_hidden_stone_control");

        world.setBlockState(hidden, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        require(context, OreScan.observeDangerFluid(bot, hidden)
                        == OreScan.Observation.UNKNOWN,
                "lava candidate became observable before the channel exposed it");
        OreDigTask fluidTask = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, initial);
        fluidTask.start(bot);
        fluidTask.tick(bot);
        Map<String, String> fluidResult = fluidTask.checkpoint();
        boolean fluidOpenedChannel = !bot.getActionPack().isMiningIdle();

        assertCheckpointFieldsEqual(context, stoneResult, fluidResult,
                "face", "direction", "leg", "steps_left", "boundary_reroute_origin",
                "controlled_strip_rear", "active_break_pos", "pending_pickup_pos");
        require(context, stoneOpenedChannel && fluidOpenedChannel
                        && world.getBlockState(forward).isOf(Blocks.STONE)
                        && world.getBlockState(hidden).isOf(Blocks.LAVA),
                "hidden fluid changed the pre-exposure channel action: stone="
                        + stoneResult + " fluid=" + fluidResult);
        fluidTask.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreRestartUnknownActiveTargetStrict", tickLimit = 40)
    public void restartKeepsUnknownActiveTargetWithoutInventingPickupDebt(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreUnknownActiveRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos hiddenOre = start.north(80);
        var world = context.getWorld();
        world.setBlockState(hiddenOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(hiddenOre.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, OreScan.observeOre(bot, hiddenOre, Set.of(Blocks.COAL_ORE))
                        == OreScan.Observation.UNKNOWN,
                "restart fixture did not keep the active target outside ordinary perception");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("active_break_pos", encode(hiddenOre));
        checkpoint.put("active_break_inventory", "0");
        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        restored.start(bot);
        Map<String, String> before = restored.checkpoint();
        restored.tick(bot);
        Map<String, String> after = restored.checkpoint();

        require(context, restored.state() == TaskState.RUNNING
                        && encode(hiddenOre).equals(before.get("active_break_pos"))
                        && encode(hiddenOre).equals(after.get("active_break_pos"))
                        && !before.containsKey("pending_pickup_pos")
                        && !after.containsKey("pending_pickup_pos")
                        && world.getBlockState(hiddenOre).isOf(Blocks.COAL_ORE),
                "unknown restart target was dropped or promoted to pickup debt: before="
                        + before + " after=" + after);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreRestartHighActiveTargetStrict", tickLimit = 20)
    public void restartRejectsIntactHighActiveBreakButPreservesGoneBreakDebt(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreHighActiveRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos highOre = fixture.start().up(5);
        var world = context.getWorld();
        world.setBlockState(highOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, OreScan.observeOre(bot, highOre, Set.of(Blocks.COAL_ORE))
                        == OreScan.Observation.OBSERVED_PRESENT,
                "intact high restart target was not factually visible");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(fixture.start(), 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("active_break_pos", encode(highOre));
        checkpoint.put("active_break_inventory", "0");
        OreDigTask intact = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        intact.start(bot);
        intact.tick(bot);
        Map<String, String> rejected = intact.checkpoint();
        require(context, intact.state() == TaskState.RUNNING
                        && world.getBlockState(highOre).isOf(Blocks.COAL_ORE)
                        && bot.getActionPack().isMiningIdle()
                        && !rejected.containsKey("active_break_pos")
                        && !rejected.containsKey("pending_pickup_pos"),
                "restart resumed an intact high-shaft break or invented pickup debt: " + rejected);
        intact.cancel(bot, "gametest_intact_high_restart_complete");

        // The same checkpoint must not erase a transaction whose block is already factually gone.
        // That case still owns a physical ItemEntity debt even though the old break pose is invalid.
        world.setBlockState(highOre, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        require(context, OreScan.observeOre(bot, highOre, Set.of(Blocks.COAL_ORE))
                        == OreScan.Observation.OBSERVED_GONE,
                "gone high restart target was not factually observable");
        OreDigTask gone = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        gone.start(bot);
        Map<String, String> preserved = gone.checkpoint();
        require(context, encode(highOre).equals(preserved.get("pending_pickup_pos"))
                        && "0".equals(preserved.get("pending_pickup_inventory"))
                        && !preserved.containsKey("active_break_pos"),
                "restart erased or misclassified a factually gone high break: " + preserved);
        gone.cancel(bot, "gametest_gone_high_restart_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchVisibleFluidSealStrict", tickLimit = 500)
    public void blindBranchSealsVisibleSideFluidAndKeepsItsExactCursor(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripVisibleFluidSealGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos forward = start.north();
        BlockPos fluid = forward.east();
        var world = context.getWorld();
        int protectedStone = MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        // The forward body is a sealed mining wall. Its exposed side lava is reachable from the
        // saved face. The final 76 stone-like parent-mission blocks are protected, while a 77th
        // block may be physically spent before the exact wall is mined without changing direction.
        world.setBlockState(forward, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(forward.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(fluid, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone));
        require(context, MaterialPalette.pickSacrificialBlockSlot(
                        bot, protectedStone).isEmpty(),
                "fluid seal selector exposed the protected parent-mission reserve");
        require(context, MaterialPalette.pickPathSupportBlockSlot(
                        bot, protectedStone).isEmpty(),
                "drop support selector exposed the protected parent-mission reserve");
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        require(context, MaterialPalette.pickSacrificialBlockSlot(
                        bot, protectedStone).isPresent(),
                "fluid seal selector did not expose stone above the protected reserve");
        require(context, MaterialPalette.pickPathSupportBlockSlot(
                        bot, protectedStone).isPresent(),
                "drop support selector did not expose stone above the protected reserve");
        require(context, ObservableWorldQuery.canObserveBlock(bot, fluid),
                "visible branch-fluid fixture did not expose its lava cell");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_branch_visible_fluid_seal"));
        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);

        task.tick(bot);
        Map<String, String> sealed = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(fluid).isOf(Blocks.COBBLESTONE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == protectedStone
                        && "0".equals(sealed.get("direction"))
                        && "12".equals(sealed.get("steps_left"))
                        && !sealed.containsKey("boundary_reroute_origin"),
                "visible side fluid did not seal without changing the branch cursor: " + sealed);

        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_fluid_seal_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, sealed);
        TaskManager.INSTANCE.assign(bot, restored,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_ore_branch_visible_fluid_seal_restore"));
        Map<String, String> restoredCheckpoint = restored.checkpoint();
        require(context, OreDigTask.inspectCheckpoint(restoredCheckpoint).isPresent()
                        && "0".equals(restoredCheckpoint.get("direction"))
                        && "12".equals(restoredCheckpoint.get("steps_left"))
                        && world.getBlockState(fluid).isOf(Blocks.COBBLESTONE),
                "fluid seal restart lost the exact cursor or physical seal: "
                        + restoredCheckpoint);

        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            assertAliveWithoutDeath(context, bot, deathBaseline);
            failIfTerminalError(context, restored);
            require(context, world.getBlockState(fluid).isOf(Blocks.COBBLESTONE)
                            && !bot.isInLava() && !bot.isSubmergedInWater(),
                    "sealed side fluid reopened or entered the branch");
            Map<String, String> live = restored.checkpoint();
            if (bot.getBlockPos().equals(forward)
                    && Integer.parseInt(live.get("steps_left")) < 12) {
                require(context, "0".equals(live.get("direction")),
                        "fluid seal rotated the resumed branch: " + live);
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                finish(context, fixture);
                return;
            }
            if (ticks.incrementAndGet() > 400) {
                context.throwGameTestException(
                        "sealed branch never advanced from its exact cursor: " + live);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchHeadFluidRestartStrict", tickLimit = 40)
    public void blindBranchSealsOneHeadSideFluidPerTickAcrossRestart(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripHeadFluidRestartGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos forward = start.north();
        BlockPos forwardHead = forward.up();
        BlockPos headWater = forwardHead.east();
        BlockPos headLava = forwardHead.west();
        var world = context.getWorld();
        int protectedStone = MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlockState(forward, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(forwardHead, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(headWater.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(headLava.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(headWater, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(headLava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone + 2));
        require(context, ObservableWorldQuery.canObserveBlock(bot, headWater)
                        && ObservableWorldQuery.canObserveBlock(bot, headLava),
                "head-fluid fixture did not expose both lateral body-envelope sources");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, checkpoint);
        task.start(bot);
        task.tick(bot);
        Map<String, String> first = task.checkpoint();
        boolean waterSealed = world.getBlockState(headWater).isOf(Blocks.COBBLESTONE);
        boolean lavaSealed = world.getBlockState(headLava).isOf(Blocks.COBBLESTONE);
        require(context, task.state() == TaskState.RUNNING
                        && waterSealed != lavaSealed
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == protectedStone + 1
                        && world.getBlockState(forward).isOf(Blocks.STONE)
                        && world.getBlockState(forwardHead).isOf(Blocks.STONE)
                        && "0".equals(first.get("direction"))
                        && "12".equals(first.get("steps_left")),
                "one blind tick did not seal exactly one head-side source before mining: "
                        + first);

        task.cancel(bot, "gametest_head_fluid_restart");
        OreDigTask restored = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, first);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> second = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING
                        && world.getBlockState(headWater).isOf(Blocks.COBBLESTONE)
                        && world.getBlockState(headLava).isOf(Blocks.COBBLESTONE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && world.getBlockState(forward).isOf(Blocks.STONE)
                        && world.getBlockState(forwardHead).isOf(Blocks.STONE)
                        && "0".equals(second.get("direction"))
                        && "12".equals(second.get("steps_left")),
                "restart did not recheck and seal the second head-side source first: " + second);
        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchHeadFluidReserveStrict", tickLimit = 20)
    public void headSideFluidCannotConsumeExactProtectedReserve(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripHeadFluidReserveGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos forward = start.north();
        BlockPos forwardHead = forward.up();
        BlockPos headLava = forwardHead.east();
        var world = context.getWorld();
        int protectedStone = MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(32)
                + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;

        world.setBlockState(forward, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(forwardHead, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(headLava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        // East is contaminated by the head source; leave one visible, solid west branch so the
        // exact-reserve result is a finite typed reroute rather than an all-directions trap.
        world.setBlockState(start.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.west().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, protectedStone));
        require(context, ObservableWorldQuery.canObserveBlock(bot, headLava),
                "exact-reserve fixture did not expose its head-side lava");

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(
                Set.of(Blocks.COAL_ORE), 1, 0, protectedStone, checkpoint);
        task.start(bot);
        task.tick(bot);
        Map<String, String> rerouted = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && world.getBlockState(headLava).isOf(Blocks.LAVA)
                        && world.getBlockState(forward).isOf(Blocks.STONE)
                        && world.getBlockState(forwardHead).isOf(Blocks.STONE)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == protectedStone
                        && "12".equals(rerouted.get("steps_left"))
                        && !rerouted.containsKey("active_break_pos")
                        && !rerouted.containsKey("pending_pickup_pos"),
                "head-fluid boundary consumed reserve or opened the body before reroute: "
                        + task.state() + ":" + task.failureReason() + " " + rerouted);
        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchDirectHeadWaterRerouteStrict", tickLimit = 20)
    public void blindBranchDoesNotSealOrMineItsDirectHeadWater(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripDirectHeadWaterGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        BlockPos headWater = start.north().up();
        var world = context.getWorld();
        world.setBlockState(headWater, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(
                Items.COBBLESTONE, MiningBudget.EMERGENCY_STONE_LIKE + 1));

        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "12");
        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);
        Map<String, String> rerouted = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && "1".equals(rerouted.get("direction"))
                        && "12".equals(rerouted.get("steps_left"))
                        && world.getBlockState(headWater).isOf(Blocks.WATER)
                        && InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE + 1,
                "direct branch-body water was sealed instead of preserving a real reroute: "
                        + rerouted);
        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreBranchBoundaryAllDangerStrict", tickLimit = 40)
    public void allObservedDangerousBranchesFailTypedAndRestartWithoutBudgetReset(
            TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreStripAllDangerGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        var world = context.getWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // First publish a factual zero-movement turn from east to south. The following restart must
        // retain exactly one conditional reverse candidate, not grant reverse to ordinary branches.
        world.setBlockState(start.east(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.west(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north().up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                openCheckpoint(start, 1, Set.of(Blocks.COAL_ORE)));
        checkpoint.put("direction", "1");
        checkpoint.put("steps_left", "48");

        OreDigTask first = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, checkpoint);
        first.start(bot);
        first.tick(bot);
        Map<String, String> firstTurn = first.checkpoint();
        require(context, first.state() == TaskState.RUNNING
                        && "2".equals(firstTurn.get("direction"))
                        && encode(start).equals(firstTurn.get("boundary_reroute_origin")),
                "all-danger fixture never published its durable first turn: " + firstTurn);
        Map<String, String> displacedMarker = new LinkedHashMap<>(firstTurn);
        displacedMarker.put("boundary_reroute_origin", encode(start.east()));
        require(context, OreDigTask.inspectCheckpoint(displacedMarker).isEmpty(),
                "checkpoint accepted a reverse exception away from its exact saved face");

        // After restart, south is rejected by lava in its current body cell. West/east are wet and the
        // conditionally eligible north reverse is lava too, so all three finite candidates fail.
        first.cancel(bot, "gametest_all_danger_restart");
        world.setBlockState(start.south(), Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north(), Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        OreDigTask failedTask = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, firstTurn);
        failedTask.start(bot);
        failedTask.tick(bot);
        require(context, failedTask.state() == TaskState.FAILED
                        && failedTask.failureReason().startsWith(
                        "ore_dig_branch_boundary_trapped:lava:"),
                "all-danger boundary did not fail with its typed first observation: "
                        + failedTask.state() + ":" + failedTask.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "all-danger boundary moved before publishing failure");
        Map<String, String> failed = failedTask.checkpoint();
        require(context, OreDigTask.inspectCheckpoint(failed).isPresent()
                        && "2".equals(failed.get("budget_used"))
                        && "2".equals(failed.get("direction"))
                        && "48".equals(failed.get("steps_left"))
                        && encode(start).equals(failed.get("boundary_reroute_origin")),
                "typed boundary failure lost its bounded restart cursor: " + failed);

        OreDigTask restored = new OreDigTask(Set.of(Blocks.COAL_ORE), 1, failed);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().equals(failedTask.failureReason()),
                "restored all-danger boundary changed its typed terminal result: "
                        + restored.state() + ":" + restored.failureReason());
        Map<String, String> retried = restored.checkpoint();
        require(context, "3".equals(retried.get("budget_used"))
                        && "2".equals(retried.get("direction"))
                        && "48".equals(retried.get("steps_left"))
                        && encode(start).equals(retried.get("boundary_reroute_origin")),
                "all-danger restart reset or mutated the durable finite cursor: " + retried);
        require(context, bot.getBlockPos().equals(start)
                        && world.getBlockState(start.east()).isOf(Blocks.WATER)
                        && world.getBlockState(start.west()).isOf(Blocks.WATER)
                        && world.getBlockState(start.south()).isOf(Blocks.LAVA)
                        && world.getBlockState(start.north()).isOf(Blocks.LAVA),
                "all-danger retry crossed or modified a rejected candidate");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void supportOreRejectsElevatedRelocationOutsideBreakEnvelope(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreSupportElevatedGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        var world = bot.getServerWorld();
        BlockPos supportOre = start.down();

        world.setBlockState(supportOre, Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        for (net.minecraft.util.math.Direction direction
                : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos side = start.offset(direction);
            world.setBlockState(side, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(side.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(side.down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos elevatedLanding = start.north().up();
        world.setBlockState(elevatedLanding.down(), Blocks.STONE.getDefaultState(),
                Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, Standability.isStandable(world, elevatedLanding),
                "fixture did not create the sole elevated relocation");
        require(context, ObservableWorldQuery.canObserveBlock(bot, supportOre),
                "support ore is not strictly observable");

        OreDigTask task = new OreDigTask(Set.of(Blocks.COAL_ORE), 1);
        task.start(bot);
        task.tick(bot); // acquire the support ore
        task.tick(bot); // reject the sole dy=+1 relocation instead of opening a path loop

        require(context, task.state() == TaskState.RUNNING,
                "awkward support ore ended the bounded search: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle(),
                "support ore started an elevated relocation outside the break envelope");
        require(context, bot.getBlockPos().equals(start),
                "support ore relocation moved before a recoverable work pose existed");
        require(context, world.getBlockState(supportOre).isOf(Blocks.COAL_ORE),
                "rejecting the elevated relocation modified the finite ore");

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void channelToolFailureReportsTheBlockedOreTier(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreChannelTierGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        BlockPos gold = fixture.start().north();
        bot.getServerWorld().setBlockState(
                gold, Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, gold, true);
        BlockMiner.Status status = miner.tick(bot);

        require(context, status == BlockMiner.Status.FAILED,
                "stone-only channel unexpectedly started mining gold");
        require(context,
                "missing_mining_channel_tool:minecraft:iron_pickaxe"
                        .equals(miner.failureReason()),
                "blocked gold reported the wrong channel tool: " + miner.failureReason());
        require(context, bot.getServerWorld().getBlockState(gold).isOf(Blocks.GOLD_ORE),
                "typed channel failure modified the gold obstruction");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void channelToolExhaustionFailsBeforeBlacklistingOrIronUse(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreChannelToolGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos wall = fixture.start().north();
        bot.getServerWorld().setBlockState(wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "48");
        int ironDamageBefore = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.IRON_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "missing channel tool was blacklisted or retried instead of failing");
        require(context, "need_mining_channel_tool:minecraft:stone_pickaxe".equals(task.failureReason()),
                "unexpected channel-tool failure: " + task.failureReason());
        require(context, bot.getServerWorld().getBlockState(wall).isOf(Blocks.STONE),
                "OreDig broke channel rock without a stone pick");
        int ironDamageAfter = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.IRON_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();
        require(context, ironDamageAfter == ironDamageBefore,
                "channel fallback consumed finite iron-pick durability");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void nearbyRestartPositionCannotReplaceTheExactSavedFace(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreExactFaceRestoreGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos savedFace = fixture.start().north(2);
        Map<String, String> checkpoint = openCheckpoint(savedFace, 1);

        OreDigTask restored = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "nearby face restore ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(fixture.start()),
                "opening the exact-face path moved outside the task tick: "
                        + bot.getBlockPos().toShortString());
        require(context, encode(savedFace).equals(restored.checkpoint().get("face")),
                "nearby restart position replaced the exact saved face: "
                        + restored.checkpoint());

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void distantCommittedCursorRebasesToTheCurrentPhysicalBranch(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreCommittedRebaseGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos staleFace = fixture.start().north(32);
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(
                staleFace, 1, Set.of(Blocks.IRON_ORE)));
        checkpoint.put("batch_open", "false");
        checkpoint.put("batches", "3");

        OreDigTask restored = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        restored.start(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "committed-cursor rebase ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, encode(fixture.start()).equals(restored.checkpoint().get("origin"))
                        && encode(fixture.start()).equals(restored.checkpoint().get("face")),
                "distant committed cursor was not rebased locally: "
                        + restored.checkpoint());
        require(context, "3".equals(restored.checkpoint().get("batches")),
                "committed-cursor rebase erased completed batch history");

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void distantOpenCursorStillRetainsTheExactSavedFace(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreOpenFaceRestoreGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos savedFace = fixture.start().north(32);
        Map<String, String> checkpoint = openCheckpoint(
                savedFace, 1, Set.of(Blocks.IRON_ORE));

        OreDigTask restored = new OreDigTask(Set.of(Blocks.IRON_ORE), 1, checkpoint);
        restored.start(bot);

        require(context, restored.state() == TaskState.RUNNING,
                "open-cursor restore ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, encode(savedFace).equals(restored.checkpoint().get("face")),
                "open batch silently abandoned its exact saved face: "
                        + restored.checkpoint());

        restored.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "oreCheckpointStrict", tickLimit = 20)
    public void stripLightingDoesNotConsumeToolServiceSticks(TestContext context) {
        PickupFixture fixture = spawnMiner(context, "OreTorchReserveGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK));
        Map<String, String> checkpoint = new LinkedHashMap<>(openCheckpoint(fixture.start(), 1));
        checkpoint.put("direction", "0");
        checkpoint.put("steps_left", "10");

        OreDigTask task = new OreDigTask(Set.of(Blocks.DIAMOND_ORE), 1, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "strip-lighting fixture ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                "strip lighting consumed incidental coal");
        require(context, InventoryAction.countItem(bot, Items.STICK) == 1,
                "strip lighting stole a tool-service stick");
        require(context, InventoryAction.countItem(bot, Items.TORCH) == 0,
                "strip lighting synthesized torches without a planned craft");

        task.cancel(bot, "gametest_complete");
        finish(context, fixture);
    }

    private static PickupFixture spawnMiner(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 3, 9));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -8; dz <= 3; dz++) {
                world.setBlockState(start.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        180.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        return new PickupFixture(name, bot, start.toImmutable());
    }

    private static BlockPos isolateCheckpointMiner(PickupFixture fixture, int dy) {
        AIPlayerEntity bot = fixture.bot();
        var world = bot.getServerWorld();
        BlockPos isolated = fixture.start().up(dy).toImmutable();
        world.setBlockState(
                isolated.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(isolated, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(isolated.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        bot.teleport(world,
                isolated.getX() + 0.5D, isolated.getY(), isolated.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        return isolated;
    }

    private static void assertStrictCapabilities(TestContext context, AIPlayerEntity bot) {
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "ore_dig_pickup_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static void assertAliveWithoutDeath(TestContext context,
                                                AIPlayerEntity bot,
                                                int deathBaseline) {
        require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                "miner died during physical pickup regression");
        require(context, deathCount(bot) == deathBaseline,
                "miner death counter changed during physical pickup regression");
    }

    private static void failIfTerminalError(TestContext context, OreDigTask task) {
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.throwGameTestException("OreDig pickup task ended as " + task.state()
                    + ":" + task.failureReason() + " checkpoint=" + task.checkpoint());
        }
    }

    private static java.util.Optional<Vec3d> nearestDiamondDropPosition(AIPlayerEntity bot,
                                                                        BlockPos pos) {
        return nearestDiamondDrop(bot, pos).map(ItemEntity::getPos);
    }

    private static java.util.Optional<ItemEntity> nearestDiamondDrop(AIPlayerEntity bot,
                                                                      BlockPos pos) {
        return bot.getServerWorld().getEntitiesByClass(
                        ItemEntity.class, new Box(pos).expand(4.0D),
                        entity -> entity.getStack().isOf(Items.DIAMOND))
                .stream()
                .min(java.util.Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)));
    }

    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS));
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Map<String, String> openCheckpoint(BlockPos face, int targetCount) {
        return openCheckpoint(face, targetCount, Set.of(Blocks.DIAMOND_ORE));
    }

    private static Map<String, String> openCheckpoint(BlockPos face,
                                                       int targetCount,
                                                       Set<Block> ores) {
        int rareMissionTarget = targetCount >= 8
                && (ores.contains(Blocks.DIAMOND_ORE)
                || ores.contains(Blocks.DEEPSLATE_DIAMOND_ORE)) ? targetCount : 0;
        return openCheckpoint(face, targetCount, ores, rareMissionTarget);
    }

    private static Map<String, String> openCheckpoint(BlockPos face,
                                                       int targetCount,
                                                       Set<Block> ores,
                                                       int rareMissionTarget) {
        return new OreDigTask.OreDigCheckpoint(
                4,
                targetCount,
                true,
                0,
                rareMissionTarget,
                false,
                40,
                0,
                0,
                MiningCursor.initial(face, 48),
                OreDigTask.oreFingerprint(ores),
                0,
                0,
                null,
                null,
                null,
                null,
                -1,
                -1,
                -1,
                null,
                -1).encode();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Deque<BlockPos> inspectVeinQueueForFixture(OreDigTask task) {
        try {
            java.lang.reflect.Field field = OreDigTask.class.getDeclaredField("veinQueue");
            field.setAccessible(true);
            return (java.util.Deque<BlockPos>) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could not inspect OreDig vein queue", exception);
        }
    }

    private static void enqueueVeinForFixture(OreDigTask task, BlockPos ore) {
        java.util.Deque<BlockPos> queue = inspectVeinQueueForFixture(task);
        queue.clear();
        queue.addLast(ore.toImmutable());
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, BlockPos> inspectRememberedHighWorkPosesForFixture(
            OreDigTask task) {
        try {
            java.lang.reflect.Field field = OreDigTask.class.getDeclaredField(
                    "rememberedHighWorkPoses");
            field.setAccessible(true);
            return (Map<BlockPos, BlockPos>) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "could not inspect OreDig remembered work poses", exception);
        }
    }

    private static void setBlockPosFieldForFixture(OreDigTask task,
                                                   String fieldName,
                                                   BlockPos value) {
        try {
            java.lang.reflect.Field field = OreDigTask.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(task, value == null ? null : value.toImmutable());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "could not set OreDig fixture field " + fieldName, exception);
        }
    }

    private static void rememberHighWorkPoseForFixture(OreDigTask task,
                                                       AIPlayerEntity bot,
                                                       BlockPos ore,
                                                       BlockPos pose) {
        try {
            java.lang.reflect.Method method = OreDigTask.class.getDeclaredMethod(
                    "rememberObservedHighWorkPose",
                    AIPlayerEntity.class, BlockPos.class, BlockPos.class);
            method.setAccessible(true);
            method.invoke(task, bot, ore, pose);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "could not publish OreDig remembered work pose", exception);
        }
    }

    private static void assertCheckpointFieldsEqual(TestContext context,
                                                    Map<String, String> expected,
                                                    Map<String, String> actual,
                                                    String... keys) {
        for (String key : keys) {
            require(context, java.util.Objects.equals(expected.get(key), actual.get(key)),
                    "checkpoint transform changed " + key + ": before=" + expected.get(key)
                            + ", after=" + actual.get(key));
        }
    }

    private static void finish(TestContext context, PickupFixture fixture) {
        require(context, HarvestCore.countInventoryItems(
                        fixture.bot(), Set.of(Items.DIAMOND))
                        == InventoryAction.countItem(fixture.bot(), Items.DIAMOND),
                "diamond accounting disagrees across inventory observers");
        AIPlayerManager.INSTANCE.despawn(
                fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record PickupFixture(String name, AIPlayerEntity bot, BlockPos start) {
    }
}
