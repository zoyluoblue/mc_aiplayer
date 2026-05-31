package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class HarvestCore {
    private HarvestCore() {
    }

    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Block targetBlock, int horizontalRadius, int down, int up) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-horizontalRadius, -down, -horizontalRadius), origin.add(horizontalRadius, up, horizontalRadius))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(targetBlock))
                .map(BlockPos::toImmutable)
                .map(pos -> targetChoice(bot, pos))
                .filter(choice -> choice != null)
                .min(Comparator.comparingDouble(choice -> choice.pos().getSquaredDistance(origin)))
                .orElse(null);
    }

    // MINE-DIG/Fix C:在一组候选方块里找最近可达的(如"任意原木"),供 GatherQuotaTask 跨树种采集。
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks, int horizontalRadius, int down, int up) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-horizontalRadius, -down, -horizontalRadius), origin.add(horizontalRadius, up, horizontalRadius))
                .filter(pos -> targetBlocks.contains(bot.getServerWorld().getBlockState(pos).getBlock()))
                .map(BlockPos::toImmutable)
                .map(pos -> targetChoice(bot, pos))
                .filter(choice -> choice != null)
                .min(Comparator.comparingDouble(choice -> choice.pos().getSquaredDistance(origin)))
                .orElse(null);
    }

    public static void startMining(AIPlayerEntity bot, BlockPos targetPos) {
        ToolSelector.equipBestTool(bot, bot.getServerWorld().getBlockState(targetPos));
        MiningAction.startMining(bot, targetPos, Direction.getFacing(bot.getEyePos().subtract(targetPos.toCenterPos())));
    }

    public static Optional<ItemEntity> nearestDrop(AIPlayerEntity bot, Item item, double radius) {
        return nearestDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static Optional<ItemEntity> nearestDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        return bot.getServerWorld()
                .getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(radius),
                        entity -> !entity.getStack().isEmpty() && matches(entity.getStack(), items))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item, double maxH, double maxV) {
        return forcePickupNearbyAnyOf(bot, item == null ? null : Set.of(item), maxH, maxV);
    }

    public static boolean forcePickupNearbyAnyOf(AIPlayerEntity bot, Set<Item> items, double maxH, double maxV) {
        Box box = bot.getBoundingBox().expand(maxH, maxV, maxH);
        List<ItemEntity> drops = bot.getServerWorld().getEntitiesByClass(ItemEntity.class, box,
                entity -> !entity.getStack().isEmpty()
                        && matches(entity.getStack(), items)
                        && canForcePickup(bot, entity, maxH, maxV));
        boolean picked = false;
        for (ItemEntity drop : drops) {
            ItemStack remaining = drop.getStack().copy();
            int before = remaining.getCount();
            ActionResult result = InventoryAction.giveItem(bot, remaining);
            int inserted = before - remaining.getCount();
            if (inserted <= 0) {
                continue;
            }
            picked = true;
            BotLog.action(bot, "pickup_forced",
                    "item", drop.getStack().getItem(),
                    "count", inserted,
                    "result", result.isSuccess() ? "all" : result.reason());
            if (remaining.isEmpty()) {
                drop.discard();
            } else {
                drop.setStack(remaining);
            }
        }
        return picked;
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item) {
        AIBotConfig.Pickup pickup = AIBotConfig.get().pickup();
        return forcePickupNearby(bot, item, pickup.forceRadiusH(), pickup.forceRadiusV());
    }

    public static boolean forcePickupNearbyAnyOf(AIPlayerEntity bot, Set<Item> items) {
        AIBotConfig.Pickup pickup = AIBotConfig.get().pickup();
        return forcePickupNearbyAnyOf(bot, items, pickup.forceRadiusH(), pickup.forceRadiusV());
    }

    public static void chaseDrop(AIPlayerEntity bot, Item item, double radius) {
        chaseDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static void chaseDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        if (forcePickupNearbyAnyOf(bot, items)) {
            bot.getActionPack().stopMovement();
            return;
        }
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.distanceTo(drop) > 1.3F) {
                if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                    ActionResult result = bot.getActionPack().startPathTo(pickupStandPos(bot, drop.getBlockPos()));
                    if (result.isFailed()) {
                        bot.getActionPack().startWalkTo(drop.getPos());
                    }
                }
            } else {
                bot.getActionPack().stopMovement();
            }
        });
    }

    public static int sweepPickup(AIPlayerEntity bot, Item item, double radius, int maxTargets) {
        return sweepPickupAnyOf(bot, item == null ? null : Set.of(item), radius, maxTargets);
    }

    public static int sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, double radius, int maxTargets) {
        int picked = 0;
        for (int i = 0; i < maxTargets; i++) {
            if (!forcePickupNearbyAnyOf(bot, items)) {
                break;
            }
            picked++;
        }
        if (picked > 0) {
            return picked;
        }
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                ActionResult result = bot.getActionPack().startPathTo(pickupStandPos(bot, drop.getBlockPos()));
                if (result.isFailed()) {
                    bot.getActionPack().startWalkTo(drop.getPos());
                }
            }
        });
        return 0;
    }

    public static int sweepPickup(AIPlayerEntity bot, Item item, int maxTargets) {
        return sweepPickup(bot, item, AIBotConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, int maxTargets) {
        return sweepPickupAnyOf(bot, items, AIBotConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int totalInventoryCount(AIPlayerEntity bot) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int countInventoryItems(AIPlayerEntity bot, Set<Item> items) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty() && matches(stack, items)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (!stack.isEmpty() && matches(stack, items)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static Set<Item> expectedDropsFor(Set<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Set.of();
        }
        java.util.LinkedHashSet<Item> result = new java.util.LinkedHashSet<>();
        for (Block block : blocks) {
            result.addAll(expectedDropsFor(block));
        }
        return Set.copyOf(result);
    }

    public static Set<Item> expectedDropsFor(Block block) {
        if (block == Blocks.STONE) {
            return Set.of(Items.COBBLESTONE);
        }
        if (block == Blocks.DEEPSLATE) {
            return Set.of(Items.COBBLED_DEEPSLATE);
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return Set.of(Items.COAL);
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            return Set.of(Items.RAW_IRON);
        }
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            return Set.of(Items.RAW_COPPER);
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
            return Set.of(Items.RAW_GOLD);
        }
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return Set.of(Items.REDSTONE);
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return Set.of(Items.LAPIS_LAZULI);
        }
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return Set.of(Items.DIAMOND);
        }
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return Set.of(Items.EMERALD);
        }
        Item item = block.asItem();
        return item == Items.AIR ? Set.of() : Set.of(item);
    }

    public static boolean isInventoryFull(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().main) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static BlockPos pickupStandPos(AIPlayerEntity bot, BlockPos itemPos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos[] candidates = {
                itemPos,
                itemPos.north(),
                itemPos.south(),
                itemPos.east(),
                itemPos.west()
        };
        for (BlockPos candidate : candidates) {
            if (!Standability.isStandable(bot.getServerWorld(), candidate)) {
                continue;
            }
            double distance = candidate.getSquaredDistance(bot.getBlockPos());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? itemPos : best;
    }

    public static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePos().distanceTo(target.toCenterPos()) <= 4.5D;
    }

    public static boolean canDirectMine(AIPlayerEntity bot, BlockPos target) {
        // 允许挖脚下/低处方块(够得着即可)——地表往下挖石头的核心。
        return canReach(bot, target);
    }

    public static TargetChoice targetChoice(AIPlayerEntity bot, BlockPos target) {
        // 不再因方块低于自己而拒绝:够得着就直接挖(挖脚下→下落→继续往下挖,掉落物随之落到脚边便于拾取)。
        if (canDirectMine(bot, target)) {
            return new TargetChoice(target, null, true);
        }
        BlockPos stand = adjacentStandPos(bot, target);
        if (stand == null) {
            return null;
        }
        return new TargetChoice(target, stand, false);
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canForcePickup(AIPlayerEntity bot, ItemEntity drop, double maxH, double maxV) {
        if (drop.cannotPickup()) {
            return false;
        }
        double dx = drop.getX() - bot.getX();
        double dz = drop.getZ() - bot.getZ();
        return dx * dx + dz * dz <= maxH * maxH && Math.abs(drop.getY() - bot.getY()) <= maxV;
    }

    private static boolean matches(ItemStack stack, Set<Item> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        return items.contains(stack.getItem());
    }

    public record TargetChoice(BlockPos pos, BlockPos stand, boolean direct) {
    }
}
