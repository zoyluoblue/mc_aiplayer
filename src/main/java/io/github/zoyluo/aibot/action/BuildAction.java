package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class BuildAction {
    private BuildAction() {
    }

    /** Найти в инвентаре BlockItem и экипировать его */
    public static boolean equipBlockItem(AIPlayerEntity player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.getItem() instanceof BlockItem) {
                return InventoryAction.equipFromSlot(player, slot) >= 0;
            }
        }
        return false;
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            return ActionResult.failed("empty_hand");
        }
        var item = stack.getItem();

        LookAction.lookAtBlock(player, against, face);
        Vec3d hitPos = Vec3d.ofCenter(against).add(
                face.getOffsetX() * 0.5D,
                face.getOffsetY() * 0.5D,
                face.getOffsetZ() * 0.5D);
        BlockHitResult hit = new BlockHitResult(hitPos, face, against, false);
        net.minecraft.util.ActionResult result = player.interactionManager.interactBlock(
                player, player.getServerWorld(), stack, hand, hit);
        if (result.isAccepted()) {
            player.swingHand(hand);
            player.updateLastActionTime();
            AStarPathfinder.invalidateCache("block_place");
            BotLog.action(player, "place", "pos", LogFields.pos(against.offset(face)), "face", face, "item", item);
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed("interact_block_" + result.getClass().getSimpleName());
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        // If hand already holds a BlockItem (e.g. from select_item), use it.
        // Only auto-equip when hand is empty or holds a non-BlockItem.
        ItemStack inHand = player.getStackInHand(Hand.MAIN_HAND);
        if (inHand.isEmpty() || !(inHand.getItem() instanceof BlockItem)) {
            if (!equipBlockItem(player)) {
                return ActionResult.failed("no_block_item_in_inventory");
            }
        }
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        BlockPos below = pos.down();
        if (!player.getServerWorld().getBlockState(below).isAir()) {
            ActionResult result = placeBlock(player, below, Direction.UP, Hand.MAIN_HAND);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = result;
        }
        for (Direction direction : Direction.values()) {
            BlockPos against = pos.offset(direction.getOpposite());
            if (!player.getServerWorld().getBlockState(against).isAir()) {
                ActionResult result = placeBlock(player, against, direction, Hand.MAIN_HAND);
                if (result.isSuccess()) {
                    return result;
                }
                lastFailure = result;
            }
        }
        ActionResult fallback = directPlaceFallback(player, pos, Hand.MAIN_HAND);
        if (fallback.isSuccess()) {
            return fallback;
        }
        return lastFailure;
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("not_block_item");
        }
        var item = stack.getItem();
        var existing = player.getServerWorld().getBlockState(pos);
        if (!existing.isAir() && !existing.isReplaceable()) {
            return ActionResult.failed("target_not_air");
        }
        player.getServerWorld().setBlockState(pos, blockItem.getBlock().getDefaultState(), 3);
        if (!player.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        player.swingHand(hand);
        player.updateLastActionTime();
        AStarPathfinder.invalidateCache("block_place_fallback");
        BotLog.action(player, "place_fallback", "pos", LogFields.pos(pos), "item", item);
        return ActionResult.SUCCESS;
    }

    /** Экипировать конкретный предмет по ID */
    public static boolean equipItem(AIPlayerEntity player, String itemId) {
        try {
            var id = net.minecraft.util.Identifier.of(itemId);
            var registry = net.minecraft.registry.Registries.ITEM;
            var opt = registry.getOptionalValue(id);
            if (opt.isEmpty()) return false;
            var item = opt.get();
            var optSlot = InventoryAction.findItem(player, item);
            if (optSlot.isEmpty()) return false;
            InventoryAction.equipFromSlot(player, optSlot.getAsInt());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
