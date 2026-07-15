package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class Standability {
    private static final Map<CacheKey, Boolean> CACHE = new ConcurrentHashMap<>(4096);
    private static volatile long version;

    private Standability() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static void invalidateAll() {
        version++;
        CACHE.clear();
    }

    public static boolean isStandable(ServerWorld world, BlockPos pos) {
        CacheKey key = new CacheKey(world.getRegistryKey().getValue().toString(), version, pos);
        Boolean cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = compute(world, pos);
        CACHE.put(key, result);
        return result;
    }

    /** A surface-water navigation cell: passable water at the feet with a clear head. */
    public static boolean isSwimmable(ServerWorld world, BlockPos pos) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        return feet.getFluidState().isIn(FluidTags.WATER)
                && feet.getCollisionShape(world, pos).isEmpty()
                && head.getCollisionShape(world, pos.up()).isEmpty()
                && !head.getFluidState().isIn(FluidTags.LAVA);
    }

    public static Optional<BlockPos> findNearestStandable(ServerWorld world,
                                                          BlockPos origin,
                                                          int horizontalRadius,
                                                          int verticalDown,
                                                          int verticalUp) {
        Optional<BlockPos> sameColumn = findStandableInColumn(world, origin, verticalDown, verticalUp);
        if (sameColumn.isPresent()) {
            return sameColumn;
        }

        int radiusLimit = Math.max(0, horizontalRadius);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 1; radius <= radiusLimit; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Optional<BlockPos> candidate = findStandableInColumn(world, origin.add(dx, 0, dz), verticalDown, verticalUp);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    double distance = candidate.get().getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        best = candidate.get();
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) {
                return Optional.of(best.toImmutable());
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findStandableInColumn(ServerWorld world, BlockPos origin, int verticalDown, int verticalUp) {
        int topY = world.getBottomY() + world.getHeight();
        int minY = Math.max(world.getBottomY() + 1, origin.getY() - Math.max(0, verticalDown));
        int maxY = Math.min(topY - 2, origin.getY() + Math.max(0, verticalUp));
        for (int y = Math.min(origin.getY(), maxY); y >= minY; y--) {
            BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
            if (isStandable(world, candidate)) {
                return Optional.of(candidate.toImmutable());
            }
        }
        for (int y = Math.max(origin.getY() + 1, minY); y <= maxY; y++) {
            BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
            if (isStandable(world, candidate)) {
                return Optional.of(candidate.toImmutable());
            }
        }
        return Optional.empty();
    }

    private static boolean compute(ServerWorld world, BlockPos pos) {
        int topY = world.getBottomY() + world.getHeight();
        if (pos.getY() < world.getBottomY() + 1 || pos.getY() >= topY - 1) {
            return false;
        }

        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        if (!feet.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        if (isDangerous(feet) || isDangerous(head) || isDangerous(below)) {
            return false;
        }
        // WATER-1:深水(脚位+头位都是水)不可作为落脚点。bot 是服务端假玩家,不会玩家式按住跳游泳
        // (WalkToController 拟人化落地单跳),入深水只会原地扑腾溺水。浅水(脚水头空气)仍可涉过。
        if (feet.getFluidState().isIn(FluidTags.WATER) && head.getFluidState().isIn(FluidTags.WATER)) {
            return false;
        }
        // NAV-11:梯子/藤蔓等可攀爬方块,站在其中即可,无需下方支撑。
        if (feet.isIn(BlockTags.CLIMBABLE)) {
            return true;
        }
        if (below.isAir()) {
            return false;
        }
        return below.getCollisionShape(world, pos.down()).getMax(Direction.Axis.Y) > 0.0D;
    }

    public static boolean isDangerous(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.isIn(FluidTags.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.POINTED_DRIPSTONE);
    }

    private record CacheKey(String dimension, long version, BlockPos pos) {
        private CacheKey {
            pos = pos.toImmutable();
        }
    }
}
