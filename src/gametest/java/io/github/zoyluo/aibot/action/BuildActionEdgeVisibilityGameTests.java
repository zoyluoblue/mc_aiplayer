package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.lang.reflect.Field;
import java.util.Set;

/** Physical strict-survival regressions for support faces exposed only at a reachable edge. */
public final class BuildActionEdgeVisibilityGameTests implements FabricGameTest {
    private static final String BATCH = "buildActionEdgeVisibilityStrict";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void supportCenterAndFaceCenterMayBeBeyondReachWhenInsetIsLegal(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos support = feet.east(5);
        BlockPos destination = support.west();
        context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "BuildReachInset", feet,
                new Vec3d(feet.getX() + 0.57D, feet.getY(), feet.getZ() + 0.5D));
        equipTwoCobblestone(bot);
        double reach = bot.getBlockInteractionRange();
        double reachSquared = reach * reach;
        Vec3d faceCenter = Vec3d.ofCenter(support).add(-0.5D, 0.0D, 0.0D);
        Vec3d reachableInset = faceCenter.add(0.0D, 0.375D, 0.0D);

        require(context, bot.getEyePos().squaredDistanceTo(support.toCenterPos()) > reachSquared,
                "fixture left the support center inside ordinary reach");
        require(context, bot.getEyePos().squaredDistanceTo(faceCenter) > reachSquared,
                "fixture left the requested face center inside ordinary reach");
        require(context, bot.getEyePos().squaredDistanceTo(reachableInset) < reachSquared,
                "fixture did not expose a reachable inset point");
        require(context, bot.canInteractWithBlockAt(support, 0.0D),
                "vanilla block-box reach rejected the inset fixture");

        ActionResult result = BuildAction.placeBlock(
                bot, support, Direction.WEST, Hand.MAIN_HAND);
        require(context, result.isSuccess(),
                "reachable face inset was rejected: " + result.reason());
        require(context, context.getWorld().getBlockState(destination).isOf(Blocks.COBBLESTONE),
                "successful inset click did not place the real destination block");
        require(context, bot.getMainHandStack().getCount() == 1,
                "inset placement did not consume exactly one physical block");
        cleanup(context, bot, "BuildReachInset");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void placeAtUsesExactInsetWhenAllTargetFaceCentersAreHidden(TestContext context) {
        BlockPos base = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, base);
        BlockPos botFeet = base.south();
        context.getWorld().setBlockState(
                botFeet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos support = base.east(4).up();
        BlockPos destination = support.west();
        BlockPos occluder = base.east().up();
        context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(occluder, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // Hide every non-clicked face center without creating another block adjacent to the
        // destination. The WEST face retains only its south-side inset around the occluder.
        for (Direction direction : new Direction[]{
                Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST}) {
            context.getWorld().setBlockState(
                    support.offset(direction), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, "BuildHiddenEdge", botFeet,
                new Vec3d(base.getX() + 0.5D, base.getY(), base.getZ() + 1.3D));
        equipTwoCobblestone(bot);
        Vec3d faceCenter = Vec3d.ofCenter(support).add(-0.5D, 0.0D, 0.0D);
        LookAction.lookAt(bot, faceCenter);
        var centerRay = bot.raycast(bot.getBlockInteractionRange(), 1.0F, false);

        require(context, !(centerRay instanceof BlockHitResult hit
                        && hit.getBlockPos().equals(support)
                        && hit.getSide() == Direction.WEST),
                "fixture left the requested face center clickable");
        require(context, !ObservableWorldQuery.canObserveBlock(bot, support),
                "fixture exposed one of the legacy six face-center rays");
        require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, support),
                "fixture did not expose an exact inset observation ray");

        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        require(context, result.isSuccess(),
                "placeBlockAt did not reach the exact inset sampler: " + result.reason());
        require(context, context.getWorld().getBlockState(destination).isOf(Blocks.COBBLESTONE),
                "edge sampler reported success without a physical placement");
        require(context, bot.getMainHandStack().getCount() == 1,
                "edge sampler did not consume exactly one physical block");
        cleanup(context, bot, "BuildHiddenEdge");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void waterSourceMayBeObservableOnlyThroughAnInsetRay(TestContext context) {
        assertFluidOnlyInsetObservable(context, "BuildInsetWater", Blocks.WATER);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void lavaSourceMayBeObservableOnlyThroughAnInsetRay(TestContext context) {
        assertFluidOnlyInsetObservable(context, "BuildInsetLava", Blocks.LAVA);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void lowPerceptionRadiusRejectsOtherwiseReachableInsetPlacement(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos support = feet.east(3).up();
        BlockPos destination = support.west();
        context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = spawn(context, "BuildLowPerception", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);
        AIBotConfig original = AIBotConfig.get();

        try {
            require(context, bot.canInteractWithBlockAt(support, 0.0D),
                    "fixture support is outside vanilla interaction reach");
            setConfig(withPerceptionRadius(original, 1));
            require(context, AIBotConfig.get().perception().radius() == 1,
                    "fixture failed to lower the configured perception radius");
            require(context, !ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, support),
                    "inset observation escaped the one-block perception radius");

            ActionResult result = BuildAction.placeBlock(
                    bot, support, Direction.WEST, Hand.MAIN_HAND);
            require(context, result.isFailed()
                            && "support_face_not_visible".equals(result.reason()),
                    "low-radius support reached vanilla interaction: " + result.reason());
            require(context, context.getWorld().getBlockState(destination).isAir(),
                    "low-radius placement mutated the external destination");
            require(context, bot.getMainHandStack().getCount() == 2,
                    "low-radius placement consumed a physical block");
        } finally {
            setConfig(original);
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), "BuildLowPerception");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void strictModeNeverUsesDirectHiddenPlacementFallback(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos unsupported = feet.north(2).up();
        AIPlayerEntity bot = spawn(context, "BuildNoFallback", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);

        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "fixture is not running under strict_survival");
        require(context, ObservableWorldQuery.canObserveCell(bot, unsupported),
                "unsupported destination is not visible enough to detect direct fallback");
        ActionResult result = BuildAction.placeBlockAt(bot, unsupported);

        require(context, result.isFailed(),
                "strict unsupported placement unexpectedly succeeded");
        require(context, context.getWorld().getBlockState(unsupported).isAir(),
                "strict placement used a direct world-mutation fallback");
        require(context, bot.getMainHandStack().getCount() == 2,
                "failed strict placement consumed a block");
        cleanup(context, bot, "BuildNoFallback");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void ordinarySupportedPlacementStillUsesVanillaInteraction(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clear(context, feet);
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos destination = feet.north();
        context.getWorld().setBlockState(
                destination.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = spawn(context, "BuildOrdinary", feet, Vec3d.ofBottomCenter(feet));
        equipTwoCobblestone(bot);

        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        require(context, result.isSuccess(),
                "ordinary supported placement regressed: " + result.reason());
        require(context, context.getWorld().getBlockState(destination).isOf(Blocks.COBBLESTONE),
                "ordinary placement did not mutate through vanilla interaction");
        require(context, bot.getMainHandStack().getCount() == 1,
                "ordinary placement did not consume exactly one physical block");
        cleanup(context, bot, "BuildOrdinary");
    }

    private static void assertFluidOnlyInsetObservable(TestContext context,
                                                       String name,
                                                       Block fluid) {
        BlockPos base = context.getAbsolutePos(new BlockPos(3, 4, 4));
        clear(context, base);
        BlockPos botFeet = base.south();
        context.getWorld().setBlockState(
                botFeet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos source = base.east(4).up();
        BlockPos occluder = base.east().up();
        context.getWorld().setBlockState(source, fluid.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(occluder, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : new Direction[]{
                Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST}) {
            context.getWorld().setBlockState(
                    source.offset(direction), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, name, botFeet,
                new Vec3d(base.getX() + 0.5D, base.getY(), base.getZ() + 1.3D));
        Vec3d faceCenter = Vec3d.ofCenter(source).add(-0.499D, 0.0D, 0.0D);
        BlockHitResult centerHit = context.getWorld().raycast(new RaycastContext(
                bot.getEyePos(), faceCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY,
                bot));

        require(context, centerHit.getType() != HitResult.Type.BLOCK
                        || !centerHit.getBlockPos().equals(source)
                        || centerHit.getSide() != Direction.WEST,
                "fixture left the fluid face center visible");
        require(context, !ObservableWorldQuery.canObserveBlock(bot, source),
                "fixture exposed a legacy fluid face-center ray");
        require(context, ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, source),
                "fluid source was not observable through its exposed inset");
        cleanup(context, bot, name);
    }

    private static AIBotConfig withPerceptionRadius(AIBotConfig config, int radius) {
        AIBotConfig.Perception perception = config.perception();
        return new AIBotConfig(
                config.profile(),
                config.operatorCapabilities(),
                config.deepseek(),
                new AIBotConfig.Perception(
                        radius,
                        perception.maxBlocks(),
                        perception.maxEntities(),
                        perception.maxItems(),
                        perception.includeRawLists()),
                config.brain(),
                config.watchdog(),
                config.logging(),
                config.survival(),
                config.combat(),
                config.night(),
                config.mining(),
                config.goal(),
                config.nav(),
                config.pickup());
    }

    private static void setConfig(AIBotConfig config) {
        try {
            Field instance = AIBotConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }

    private static AIPlayerEntity spawn(TestContext context,
                                        String name,
                                        BlockPos feet,
                                        Vec3d pose) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(), pose,
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), pose.x, pose.y, pose.z,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        require(context, bot.getBlockPos().equals(feet),
                "fixture spawned in the wrong feet cell: " + bot.getBlockPos().toShortString());
        return bot;
    }

    private static void equipTwoCobblestone(AIPlayerEntity bot) {
        bot.getInventory().selectedSlot = 0;
        bot.getInventory().main.set(0, new ItemStack(Items.COBBLESTONE, 2));
        bot.getInventory().markDirty();
    }

    private static void clear(TestContext context, BlockPos origin) {
        for (int dx = -2; dx <= 7; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    context.getWorld().setBlockState(
                            origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static void cleanup(TestContext context, AIPlayerEntity bot, String name) {
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
