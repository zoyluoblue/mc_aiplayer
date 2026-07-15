package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.OptionalInt;

public final class FarmAction {
    private FarmAction() {
    }

    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {
        ServerWorld world = bot.getServerWorld();
        if (!isTillable(world.getBlockState(ground)) || !world.getBlockState(ground.up()).isAir()) {
            return ActionResult.failed("not_tillable");
        }
        OptionalInt hoeSlot = findHoeSlot(bot);
        if (hoeSlot.isEmpty()) {
            return ActionResult.failed("missing_hoe");
        }
        InventoryAction.equipFromSlot(bot, hoeSlot.getAsInt());
        world.setBlockState(ground, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        BotLog.action(bot, "till", "pos", ground);
        return ActionResult.SUCCESS;
    }

    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {
        ServerWorld world = bot.getServerWorld();
        if (!world.getBlockState(farmland).isOf(Blocks.FARMLAND) || !world.getBlockState(farmland.up()).isAir()) {
            return ActionResult.failed("not_empty_farmland");
        }
        if (!InventoryAction.removeItems(bot, seed, 1)) {
            return ActionResult.failed("missing " + seed + " x1");
        }
        world.setBlockState(farmland.up(), crop.getDefaultState(), Block.NOTIFY_ALL);
        BotLog.action(bot, "plant", "pos", farmland.up(), "seed", seed, "crop", crop);
        return ActionResult.SUCCESS;
    }

    public static boolean isMature(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMature(state);
    }

    public static ActionResult harvest(AIPlayerEntity bot, BlockPos cropPos) {
        ServerWorld world = bot.getServerWorld();
        if (!isMature(world, cropPos)) {
            return ActionResult.failed("not_mature");
        }
        world.breakBlock(cropPos, true, bot);
        BotLog.action(bot, "harvest", "pos", cropPos);
        return ActionResult.SUCCESS;
    }

    // 灌溉:用水桶在 pos 放一个水源(简化:直接 setBlockState WATER 源 + 背包 WATER_BUCKET→BUCKET)。
    public static ActionResult placeWater(AIPlayerEntity bot, BlockPos pos) {
        ServerWorld world = bot.getServerWorld();
        BlockState at = world.getBlockState(pos);
        if (!at.isAir() && !at.isOf(Blocks.WATER) && world.getFluidState(pos).isEmpty()) {
            return ActionResult.failed("not_empty"); // 目标被实心方块占,放不了水
        }
        if (!InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1)) {
            return ActionResult.failed("missing_water_bucket");
        }
        world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        ItemStack empty = new ItemStack(Items.BUCKET, 1);
        if (InventoryAction.giveItem(bot, empty).isFailed() && !empty.isEmpty()) {
            bot.dropItem(empty, false, true); // 背包满:空桶落地,别凭空消失
        }
        BotLog.action(bot, "place_water", "pos", pos);
        return ActionResult.SUCCESS;
    }

    // 灌溉:从 pos 的水源舀水进空桶(无限水源的"可再生"凭此验证:舀走一格,邻格的源会回填)。
    public static ActionResult fillBucket(AIPlayerEntity bot, BlockPos pos) {
        ServerWorld world = bot.getServerWorld();
        if (!isWaterSource(world, pos)) {
            return ActionResult.failed("not_water_source");
        }
        if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
            return ActionResult.failed("missing_bucket");
        }
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        ItemStack filled = new ItemStack(Items.WATER_BUCKET, 1);
        if (InventoryAction.giveItem(bot, filled).isFailed() && !filled.isEmpty()) {
            bot.dropItem(filled, false, true); // 背包满:水桶落地,别凭空消失
        }
        BotLog.action(bot, "fill_bucket", "pos", pos);
        return ActionResult.SUCCESS;
    }

    public static boolean isWaterSource(ServerWorld world, BlockPos pos) {
        net.minecraft.fluid.FluidState fluid = world.getFluidState(pos);
        return fluid.isIn(net.minecraft.registry.tag.FluidTags.WATER) && fluid.isStill();
    }

    public static CropSpec cropSpec(String cropName) {
        return switch (cropName) {
            case "wheat", "minecraft:wheat" -> new CropSpec(Items.WHEAT_SEEDS, Blocks.WHEAT, "wheat");
            case "carrot", "carrots", "minecraft:carrot", "minecraft:carrots" -> new CropSpec(Items.CARROT, Blocks.CARROTS, "carrot");
            case "potato", "potatoes", "minecraft:potato", "minecraft:potatoes" -> new CropSpec(Items.POTATO, Blocks.POTATOES, "potato");
            default -> throw new IllegalArgumentException("unknown_crop: " + cropName);
        };
    }

    public static boolean isSupportedCrop(Block block) {
        return block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES;
    }

    public static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (crop == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (crop == Blocks.POTATOES) {
            return Items.POTATO;
        }
        throw new IllegalArgumentException("unknown_crop_block: " + crop);
    }

    public static boolean isTillable(BlockState state) {
        return state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT);
    }

    private static OptionalInt findHoeSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.getItem() instanceof HoeItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public record CropSpec(Item seed, Block crop, String name) {
    }
}
