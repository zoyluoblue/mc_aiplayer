package io.github.zoyluo.aibot.mining;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

public final class OreScan {
    /**
     * Factual result of evaluating one world-state predicate through the bot's ordinary
     * perception. UNKNOWN is deliberately distinct from absence: callers must retain ownership
     * until the cell becomes observable instead of turning temporary occlusion into a vanished
     * ore, an invented pickup debt, or a topology decision.
     */
    public enum Observation {
        OBSERVED_PRESENT,
        OBSERVED_GONE,
        UNKNOWN
    }

    public static final Set<Block> COMMON_ORES = Set.of(
            Blocks.COAL_ORE,
            Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE,
            Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.GOLD_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE);

    private OreScan() {
    }

    /**
     * Expands a vein through positions the bot is allowed to perceive. The bot parameter is
     * intentional: production callers cannot accidentally invoke a raw server-world ore flood.
     */
    public static List<BlockPos> veinFrom(AIPlayerEntity bot, BlockPos seed, Set<Block> ores, int cap) {
        var world = bot.getServerWorld();
        if (!ObservableWorldQuery.canObserveBlock(bot, seed)) {
            return List.of();
        }
        BlockState seedState = world.getBlockState(seed);
        if (!isOre(seedState, ores)) {
            return List.of();
        }
        Block seedBlock = seedState.getBlock();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> result = new ArrayList<>();
        open.add(seed.toImmutable());
        seen.add(seed.toImmutable());
        while (!open.isEmpty() && result.size() < cap) {
            BlockPos current = open.removeFirst();
            if (!ObservableWorldQuery.canObserveBlock(bot, current)) {
                continue;
            }
            if (!world.getBlockState(current).isOf(seedBlock)) {
                continue;
            }
            result.add(current);
            // 26 邻泛洪:MC 矿脉生成常以斜对角连接(同簇噪声),只查 6 面邻会把一条脉拦腰漏一半。
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.add(dx, dy, dz).toImmutable();
                        if (!seen.add(next)) {
                            continue;
                        }
                        if (!ObservableWorldQuery.canObserveBlock(bot, next)) {
                            continue;
                        }
                        if (world.getBlockState(next).isOf(seedBlock)) {
                            open.addLast(next);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static boolean isOre(BlockState state, Set<Block> ores) {
        return ores.contains(state.getBlock());
    }

    /**
     * Evaluates a block-state predicate only after the target cell or one of its real block faces
     * is observable. Both probes are content-independent; the world state is not read merely to
     * choose which visibility query to apply.
     */
    public static Observation observe(AIPlayerEntity bot,
                                      BlockPos pos,
                                      Predicate<BlockState> predicate) {
        if (bot == null || pos == null || predicate == null
                || !ObservableWorldQuery.canObserveCell(bot, pos)
                && !ObservableWorldQuery.canObserveBlock(bot, pos)) {
            return Observation.UNKNOWN;
        }
        return predicate.test(bot.getServerWorld().getBlockState(pos))
                ? Observation.OBSERVED_PRESENT
                : Observation.OBSERVED_GONE;
    }

    public static Observation observeOre(AIPlayerEntity bot,
                                         BlockPos pos,
                                         Set<Block> ores) {
        return observe(bot, pos, state -> isOre(state, ores));
    }

    public static Observation observeAnyOre(AIPlayerEntity bot, BlockPos pos) {
        return observe(bot, pos, state -> isOreBlock(state.getBlock()));
    }

    public static Observation observeAir(AIPlayerEntity bot, BlockPos pos) {
        return observe(bot, pos, BlockState::isAir);
    }

    public static Observation observeDangerFluid(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null
                || !ObservableWorldQuery.canObserveCell(bot, pos)
                && !ObservableWorldQuery.canObserveBlock(bot, pos)
                && !ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos)) {
            return Observation.UNKNOWN;
        }
        FluidState fluid = bot.getServerWorld().getBlockState(pos).getFluidState();
        return fluid.isIn(FluidTags.LAVA) || fluid.isIn(FluidTags.WATER)
                ? Observation.OBSERVED_PRESENT
                : Observation.OBSERVED_GONE;
    }

    public static boolean isOreBlock(Block block) {
        return COMMON_ORES.contains(block) || Registries.BLOCK.getId(block).getPath().endsWith("_ore");
    }

    public static Set<Block> oreFamily(Block block) {
        Set<Block> result = new LinkedHashSet<>();
        result.add(block);
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            result.add(Blocks.COAL_ORE);
            result.add(Blocks.DEEPSLATE_COAL_ORE);
        } else if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            result.add(Blocks.IRON_ORE);
            result.add(Blocks.DEEPSLATE_IRON_ORE);
        } else if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            result.add(Blocks.COPPER_ORE);
            result.add(Blocks.DEEPSLATE_COPPER_ORE);
        } else if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
            result.add(Blocks.GOLD_ORE);
            result.add(Blocks.DEEPSLATE_GOLD_ORE);
        } else if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            result.add(Blocks.REDSTONE_ORE);
            result.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        } else if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            result.add(Blocks.LAPIS_ORE);
            result.add(Blocks.DEEPSLATE_LAPIS_ORE);
        } else if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            result.add(Blocks.DIAMOND_ORE);
            result.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        } else if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            result.add(Blocks.EMERALD_ORE);
            result.add(Blocks.DEEPSLATE_EMERALD_ORE);
        }
        return Set.copyOf(result);
    }

    public static Set<Block> expandOreFamilies(Set<Block> ores) {
        if (ores == null || ores.isEmpty()) {
            return Set.of();
        }
        Set<Block> result = new LinkedHashSet<>();
        for (Block ore : ores) {
            result.addAll(oreFamily(ore));
        }
        return Set.copyOf(result);
    }

    public static int preferredMiningY(Set<Block> ores) {
        if (ores == null || ores.isEmpty()) {
            return 16;
        }
        if (containsAny(ores, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
                Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
                Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)) {
            return -54;
        }
        if (containsAny(ores, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE)) {
            return -16;
        }
        if (containsAny(ores, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)) {
            return 16;
        }
        if (containsAny(ores, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
                Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
                Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE)) {
            return 48;
        }
        return 16;
    }

    private static boolean containsAny(Set<Block> blocks, Block... candidates) {
        for (Block candidate : candidates) {
            if (blocks.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Legacy OPERATOR-profile probe; strict-survival callers must use the bot-aware overload. */
    public static boolean adjacentHazard(World world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            FluidState fluid = world.getFluidState(adjacent);
            if (fluid.isIn(FluidTags.LAVA) || fluid.isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bot-aware adjacent-fluid classification. Unknown neighbours are never read and cannot be
     * mistaken for dry space; an observed hazard wins, otherwise any unknown neighbour keeps the
     * aggregate UNKNOWN until the newly exposed envelope can be checked again.
     */
    public static Observation adjacentHazard(AIPlayerEntity bot, BlockPos pos) {
        boolean unknown = false;
        for (Direction direction : Direction.values()) {
            Observation observation = observeDangerFluid(bot, pos.offset(direction));
            if (observation == Observation.OBSERVED_PRESENT) {
                return Observation.OBSERVED_PRESENT;
            }
            unknown |= observation == Observation.UNKNOWN;
        }
        return unknown ? Observation.UNKNOWN : Observation.OBSERVED_GONE;
    }
}
