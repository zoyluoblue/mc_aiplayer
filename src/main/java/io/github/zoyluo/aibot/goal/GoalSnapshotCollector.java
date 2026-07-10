package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GoalSnapshotCollector {
    private static final int STATION_RADIUS = 8;
    private static final int CONTAINER_RADIUS = 16;

    private GoalSnapshotCollector() {
    }

    public record Context(
            BlockPos origin,
            Set<BlockPos> boundContainers,
            BlueprintSchema blueprint,
            BlockPos buildAnchor,
            int buildPlaced,
            int buildSkipped
    ) {
        public Context {
            origin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
            boundContainers = boundContainers == null ? Set.of() : boundContainers.stream()
                    .map(BlockPos::toImmutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
            buildAnchor = buildAnchor == null ? null : buildAnchor.toImmutable();
        }

        public static Context at(BlockPos origin) {
            return new Context(origin, Set.of(), null, null, 0, 0);
        }
    }

    public static GoalSnapshot collect(AIPlayerEntity bot, Goal goal, Context context) {
        Context resolved = context == null ? Context.at(bot.getBlockPos()) : context;
        Map<String, Integer> inventory = inventoryCounts(bot);
        Set<String> capabilities = armorCapabilities(bot);
        if (goal instanceof Goal.Workstation || goal instanceof Goal.Stockpile) {
            CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "goal_postcondition");
        }
        Map<String, Integer> nearbyBlocks = goal instanceof Goal.Workstation
                ? stationCounts(bot, resolved.origin()) : Map.of();
        Map<String, Integer> containerItems = goal instanceof Goal.Stockpile
                ? containerCounts(bot, resolved) : Map.of();
        int foodUnits = goal instanceof Goal.Food ? foodUnits(inventory) : 0;
        Optional<StructureReport> structure = Optional.empty();
        if (goal instanceof Goal.Build && resolved.blueprint() != null && resolved.buildAnchor() != null) {
            structure = Optional.of(StructureVerifier.verify(bot.getServerWorld(), resolved.blueprint(),
                    resolved.buildAnchor(), resolved.buildPlaced(), resolved.buildSkipped()));
        }
        return new GoalSnapshot(inventory, ToolTier.bestPickaxeTier(bot), capabilities,
                nearbyBlocks, containerItems, foodUnits, structure);
    }

    private static Map<String, Integer> inventoryCounts(AIPlayerEntity bot) {
        Map<String, Integer> counts = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(bot.getInventory().main);
        stacks.addAll(bot.getInventory().offHand);
        stacks.addAll(bot.getInventory().armor);
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && !nearlyBroken(stack)) {
                counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static Set<String> armorCapabilities(AIPlayerEntity bot) {
        Set<String> capabilities = new HashSet<>();
        for (ItemStack stack : allStacks(bot)) {
            if (stack.isEmpty() || nearlyBroken(stack)) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.IRON_HELMET || item == Items.DIAMOND_HELMET || item == Items.NETHERITE_HELMET) {
                capabilities.add("helmet");
            } else if (item == Items.IRON_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE) {
                capabilities.add("chestplate");
            } else if (item == Items.IRON_LEGGINGS || item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS) {
                capabilities.add("leggings");
            } else if (item == Items.IRON_BOOTS || item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS) {
                capabilities.add("boots");
            } else if (item == Items.IRON_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD) {
                capabilities.add("sword");
            }
        }
        return capabilities;
    }

    private static List<ItemStack> allStacks(AIPlayerEntity bot) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(bot.getInventory().main);
        stacks.addAll(bot.getInventory().offHand);
        stacks.addAll(bot.getInventory().armor);
        return stacks;
    }

    private static Map<String, Integer> stationCounts(AIPlayerEntity bot, BlockPos origin) {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.iterateOutwards(origin, STATION_RADIUS, 4, STATION_RADIUS)) {
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            var state = bot.getServerWorld().getBlockState(pos);
            if (state.isOf(Blocks.CRAFTING_TABLE)) {
                counts.merge("minecraft:crafting_table", 1, Integer::sum);
            } else if (state.isOf(Blocks.FURNACE)) {
                counts.merge("minecraft:furnace", 1, Integer::sum);
            } else if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                counts.merge("minecraft:chest", 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> containerCounts(AIPlayerEntity bot, Context context) {
        List<BlockPos> positions = context.boundContainers().isEmpty()
                ? scanContainerPositions(bot, context.origin())
                : List.copyOf(context.boundContainers());
        Set<Inventory> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : positions) {
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            Inventory inventory = ContainerAction.resolve(bot, pos).orElse(null);
            if (inventory == null || !unique.add(inventory)) {
                continue;
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty()) {
                    counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    private static List<BlockPos> scanContainerPositions(AIPlayerEntity bot, BlockPos origin) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterateOutwards(origin, CONTAINER_RADIUS, 6, CONTAINER_RADIUS)) {
            if (ObservableWorldQuery.canObserveBlock(bot, pos)
                    && bot.getServerWorld().getBlockEntity(pos) instanceof Inventory) {
                positions.add(pos.toImmutable());
            }
        }
        return positions;
    }

    private static int foodUnits(Map<String, Integer> inventory) {
        int units = 0;
        for (Item item : List.of(
                Items.COOKED_BEEF, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_PORKCHOP,
                Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BAKED_POTATO, Items.BREAD)) {
            units += inventory.getOrDefault(Registries.ITEM.getId(item).toString(), 0);
        }
        units += inventory.getOrDefault(Registries.ITEM.getId(Items.SWEET_BERRIES).toString(), 0) / 2;
        return units;
    }

    private static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1;
    }
}
