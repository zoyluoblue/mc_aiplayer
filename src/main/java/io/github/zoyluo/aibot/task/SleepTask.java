package io.github.zoyluo.aibot.task;

import com.mojang.datafixers.util.Either;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.OptionalInt;

public final class SleepTask extends AbstractTask {
    private enum Phase {
        FIND_BED,
        PLACE_BED,
        WALK_TO_BED,
        SLEEP,
        WAIT_MORNING
    }

    private Phase phase = Phase.FIND_BED;
    private BlockPos bedPos;
    private BlockPos standPos;
    private int sleepWaitTicks;

    @Override
    public String name() {
        return "sleep";
    }

    @Override
    public String describe() {
        return "Sleeping phase=" + phase + " bed=" + (bedPos == null ? "(pending)" : compact(bedPos));
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_BED -> 0.05D;
            case PLACE_BED -> 0.2D;
            case WALK_TO_BED -> 0.45D;
            case SLEEP -> 0.7D;
            case WAIT_MORNING -> Math.min(0.95D, 0.7D + elapsed / 2400.0D);
        };
    }

    @Override
    public boolean isWaiting() {
        return phase == Phase.WAIT_MORNING;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BED;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 3000) {
            fail("sleep_timeout");
            return;
        }
        switch (phase) {
            case FIND_BED -> findBed(bot);
            case PLACE_BED -> placeBed(bot);
            case WALK_TO_BED -> walkToBed(bot);
            case SLEEP -> sleep(bot);
            case WAIT_MORNING -> waitMorning(bot);
        }
    }

    private void findBed(AIPlayerEntity bot) {
        bedPos = findNearbyBed(bot, 8);
        if (bedPos == null) {
            bedPos = rememberedBed(bot);
        }
        if (bedPos != null) {
            standPos = adjacentStandPos(bot, bedPos);
            phase = Phase.WALK_TO_BED;
            return;
        }
        if (findBedItemSlot(bot).isEmpty()) {
            fail("no_bed");
            return;
        }
        phase = Phase.PLACE_BED;
    }

    private void placeBed(AIPlayerEntity bot) {
        OptionalInt bedSlot = findBedItemSlot(bot);
        if (bedSlot.isEmpty()) {
            fail("no_bed");
            return;
        }
        BedPlacement placement = chooseBedPlacement(bot);
        if (placement == null) {
            fail("no_place_for_bed");
            return;
        }
        int hotbarSlot = InventoryAction.equipFromSlot(bot, bedSlot.getAsInt());
        if (hotbarSlot < 0) {
            fail("cannot_equip_bed");
            return;
        }
        ItemStack stack = bot.getMainHandStack();
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof BedBlock bedBlock)) {
            fail("selected_item_not_bed");
            return;
        }
        ServerWorld world = bot.getServerWorld();
        BlockState foot = bedBlock.getDefaultState()
                .with(HorizontalFacingBlock.FACING, placement.facing())
                .with(BedBlock.PART, BedPart.FOOT);
        BlockState head = bedBlock.getDefaultState()
                .with(HorizontalFacingBlock.FACING, placement.facing())
                .with(BedBlock.PART, BedPart.HEAD);
        world.setBlockState(placement.foot(), foot, Block.NOTIFY_ALL);
        world.setBlockState(placement.head(), head, Block.NOTIFY_ALL);
        if (!bot.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        bedPos = placement.foot();
        standPos = adjacentStandPos(bot, bedPos);
        phase = Phase.WALK_TO_BED;
    }

    private void walkToBed(AIPlayerEntity bot) {
        if (bedPos == null || !(bot.getServerWorld().getBlockState(bedPos).getBlock() instanceof BedBlock)) {
            phase = Phase.FIND_BED;
            return;
        }
        if (bot.getEyePos().distanceTo(bedPos.toCenterPos()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = Phase.SLEEP;
            return;
        }
        if (standPos == null) {
            standPos = adjacentStandPos(bot, bedPos);
        }
        if (standPos == null) {
            fail("bed_not_reachable");
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(standPos);
        }
    }

    private void sleep(AIPlayerEntity bot) {
        Either<PlayerEntity.SleepFailureReason, Unit> result = bot.trySleep(bedPos);
        if (result.left().isPresent()) {
            fail("sleep_failed:" + result.left().get().name().toLowerCase());
            return;
        }
        sleepWaitTicks = 0;
        phase = Phase.WAIT_MORNING;
    }

    private void waitMorning(AIPlayerEntity bot) {
        sleepWaitTicks++;
        if (bot.getServerWorld().isDay()) {
            if (bot.isSleeping()) {
                bot.wakeUp();
            }
            complete();
            return;
        }
        if (sleepWaitTicks > 1200) {
            if (bot.isSleeping()) {
                bot.wakeUp();
            }
            fail("sleep_quorum_not_met");
        }
    }

    private static BlockPos findNearbyBed(AIPlayerEntity bot, int radius) {
        return findBedNear(bot, bot.getBlockPos(), radius);
    }

    private static BlockPos findBedNear(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(center.add(-radius, -3, -radius), center.add(radius, 3, radius))
                .map(BlockPos::toImmutable)
                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).getBlock() instanceof BedBlock)
                .min((left, right) -> Double.compare(left.getSquaredDistance(origin), right.getSquaredDistance(origin)))
                .orElse(null);
    }

    public static boolean hasBedAccess(AIPlayerEntity bot) {
        return findNearbyBed(bot, 8) != null || rememberedBed(bot) != null || findBedItemSlot(bot).isPresent();
    }

    private static BlockPos rememberedBed(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "bed", "home", "base")
                .map(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                        && bot.getServerWorld().getBlockState(pos).getBlock() instanceof BedBlock
                        ? pos.toImmutable()
                        : findBedNear(bot, pos, 4))
                .orElse(null);
    }

    private static OptionalInt findBedItemSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof BedBlock) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    private static BedPlacement chooseBedPlacement(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        Direction first = bot.getHorizontalFacing();
        for (Direction facing : orderedDirections(first)) {
            for (BlockPos foot : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(2, 1, 2))) {
                BlockPos footPos = foot.toImmutable();
                BlockPos headPos = footPos.offset(facing);
                if (canPlaceBedAt(bot, footPos, headPos) && adjacentStandPos(bot, footPos) != null) {
                    return new BedPlacement(footPos, headPos, facing);
                }
            }
        }
        return null;
    }

    private static boolean canPlaceBedAt(AIPlayerEntity bot, BlockPos foot, BlockPos head) {
        ServerWorld world = bot.getServerWorld();
        BlockPos botFeet = bot.getBlockPos();
        if (foot.equals(botFeet) || foot.equals(botFeet.up()) || head.equals(botFeet) || head.equals(botFeet.up())) {
            return false;
        }
        return world.getBlockState(foot).isAir()
                && world.getBlockState(head).isAir()
                && !world.getBlockState(foot.down()).isAir()
                && !world.getBlockState(head.down()).isAir();
    }

    private static Direction[] orderedDirections(Direction first) {
        return switch (first) {
            case NORTH -> new Direction[]{Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH};
            case SOUTH -> new Direction[]{Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.NORTH};
            case EAST -> new Direction[]{Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST};
            case WEST -> new Direction[]{Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST};
            default -> new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        };
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record BedPlacement(BlockPos foot, BlockPos head, Direction facing) {
    }
}
