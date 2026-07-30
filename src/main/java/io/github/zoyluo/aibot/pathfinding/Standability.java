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
        int originY = Math.max(minY, Math.min(maxY, origin.getY()));
        int maxDelta = Math.max(originY - minY, maxY - originY);
        for (int delta = 0; delta <= maxDelta; delta++) {
            // Preserve the safer lower-cell tie break, but compare vertical distance before
            // direction. The old two-pass scan searched as many as 128 blocks downward before
            // considering a stand only one block above, so a surface waypoint could resolve into
            // a cave below it even though the obstacle itself was directly jumpable.
            int downY = originY - delta;
            if (downY >= minY) {
                BlockPos candidate = new BlockPos(origin.getX(), downY, origin.getZ());
                if (isStandable(world, candidate)) {
                    return Optional.of(candidate.toImmutable());
                }
            }
            int upY = originY + delta;
            if (delta > 0 && upY <= maxY) {
                BlockPos candidate = new BlockPos(origin.getX(), upY, origin.getZ());
                if (isStandable(world, candidate)) {
                    return Optional.of(candidate.toImmutable());
                }
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
        // "Standable" is a dry footing contract. A water cell has no collision shape and used to
        // pass the checks below whenever it had a solid lake bed, so A* emitted DROP_DOWN nodes
        // into water. Clientless fake players cannot execute normal swimming travel; NavSafety
        // would lift them back onto the bank and the unchanged path immediately dropped them in
        // again. Dedicated rescue code may traverse water explicitly, ordinary navigation may not.
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        if (!feet.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        if (isDangerous(feet) || isDangerous(head) || isDangerous(below)) {
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
