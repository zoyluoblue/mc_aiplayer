package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.OptionalInt;

/**
 * Real survival bucket interactions.
 *
 * <p>The bucket item owns Minecraft's fluid rules, sounds, inventory exchange and game events.
 * These adapters therefore aim the bot and invoke the same item-use entry point as a player;
 * they never synthesize fluids or replace inventory stacks directly.</p>
 */
public final class BucketAction {
    private BucketAction() {
    }

    /** Fill one empty bucket from an observable still-water source. */
    public static ActionResult fillWaterSource(AIPlayerEntity bot, BlockPos source) {
        var world = bot.getServerWorld();
        if (!withinReach(bot, source) || !bot.canInteractWithBlockAt(source, 0.0D)) {
            return ActionResult.failed("water_source_out_of_reach");
        }
        OptionalInt slot = InventoryAction.findItem(bot, Items.BUCKET);
        if (slot.isEmpty()) {
            return ActionResult.failed("missing_bucket");
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAt(bot, source.toCenterPos());

        var lookedAt = raycastWaterSource(bot);
        if (!(lookedAt instanceof BlockHitResult hit) || !hit.getBlockPos().equals(source)) {
            return ActionResult.failed("water_source_face_not_visible");
        }
        // The source-only bucket ray is the perception boundary. Read the fluid only after the
        // same ray a player uses has actually hit this remembered source cell.
        var fluid = world.getFluidState(source);
        if (!fluid.isIn(FluidTags.WATER) || !fluid.isStill()) {
            return ActionResult.failed("not_water_source");
        }

        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
        int waterAfter = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        if (result.isSuccess() && waterAfter > waterBefore) {
            finish(bot, "fill_water_bucket", source);
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed(result.isFailed()
                ? result.reason()
                : "bucket_fill_without_inventory_change");
    }

    /**
     * Place water in {@code support.offset(face)} by aiming a water bucket at a real support face.
     */
    public static ActionResult placeWater(AIPlayerEntity bot, BlockPos support, Direction face) {
        BlockPos destination = support.offset(face);
        var world = bot.getServerWorld();
        if (!ObservableWorldQuery.canObserveCell(bot, destination)) {
            return ActionResult.failed("water_placement_not_visible");
        }
        if (!withinReach(bot, support.toCenterPos().add(
                face.getOffsetX() * 0.5D,
                face.getOffsetY() * 0.5D,
                face.getOffsetZ() * 0.5D))
                || !bot.canInteractWithBlockAt(support, 0.0D)) {
            return ActionResult.failed("water_support_out_of_reach");
        }
        OptionalInt slot = InventoryAction.findItem(bot, Items.WATER_BUCKET);
        if (slot.isEmpty()) {
            return ActionResult.failed("missing_water_bucket");
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAtBlock(bot, support, face);

        double reach = bot.getBlockInteractionRange();
        var lookedAt = bot.raycast(reach, 1.0F, false);
        if (!(lookedAt instanceof BlockHitResult hit)
                || !hit.getBlockPos().equals(support)
                || hit.getSide() != face) {
            return ActionResult.failed("water_support_face_not_visible");
        }
        // The real no-fluid ray above is the visibility boundary for supports underneath fluid.
        if (world.getBlockState(support).isAir()) {
            return ActionResult.failed("missing_water_support");
        }
        var destinationState = world.getBlockState(destination);
        if (!destinationState.isAir() && !destinationState.canBucketPlace(net.minecraft.fluid.Fluids.WATER)) {
            return ActionResult.failed("water_destination_blocked");
        }

        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        int emptyBefore = InventoryAction.countItem(bot, Items.BUCKET);
        ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
        int waterAfter = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        int emptyAfter = InventoryAction.countItem(bot, Items.BUCKET);
        if (result.isSuccess() && waterAfter < waterBefore && emptyAfter > emptyBefore) {
            finish(bot, "place_water_bucket", destination);
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed(result.isFailed()
                ? result.reason()
                : "bucket_place_without_inventory_change");
    }

    private static boolean withinReach(AIPlayerEntity bot, BlockPos pos) {
        return withinReach(bot, pos.toCenterPos());
    }

    private static boolean withinReach(AIPlayerEntity bot, net.minecraft.util.math.Vec3d pos) {
        double reach = bot.getBlockInteractionRange();
        return bot.getEyePos().squaredDistanceTo(pos) <= reach * reach;
    }

    /** Matches BucketItem's SOURCE_ONLY ray so nearer flowing water does not hide its source. */
    private static HitResult raycastWaterSource(AIPlayerEntity bot) {
        double reach = bot.getBlockInteractionRange();
        Vec3d start = bot.getEyePos();
        Vec3d end = start.add(bot.getRotationVec(1.0F).multiply(reach));
        return bot.getServerWorld().raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.SOURCE_ONLY,
                bot));
    }

    private static void finish(AIPlayerEntity bot, String action, BlockPos pos) {
        bot.swingHand(Hand.MAIN_HAND);
        bot.updateLastActionTime();
        AStarPathfinder.invalidateCache("bucket_fluid_change");
        BotLog.action(bot, action, "pos", LogFields.pos(pos));
    }
}
