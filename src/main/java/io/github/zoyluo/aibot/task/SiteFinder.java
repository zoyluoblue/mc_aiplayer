package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Optional;
import java.util.OptionalInt;

public final class SiteFinder {
    private static final double MAX_SCORE = 2.0D;

    private SiteFinder() {
    }

    public static Optional<BlockPos> findSite(AIPlayerEntity bot, int footprintX, int footprintZ, int searchRadius) {
        return findSite(bot, footprintX, footprintZ, searchRadius, false);
    }

    // lenient=true(配合 BuildTask flatten):真实起伏地形罕有现成平地→放宽,选【最平的可用点】
    //(高差≤5、不踩水/虚空、离出生面±8内),交 FLATTEN 阶段挖高填低整平。
    // lenient=false 保持严格(平整画布/无整地时不乱选坡地,零回归)。
    public static Optional<BlockPos> findSite(AIPlayerEntity bot, int footprintX, int footprintZ, int searchRadius, boolean lenient) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int ySpread = lenient ? 8 : 4;
        int maxRange = lenient ? 5 : 2;
        double scoreCap = lenient ? Double.MAX_VALUE : MAX_SCORE;
        int originSurface = standableY(world, origin.getX(), origin.getZ(), origin.getY()).orElse(origin.getY());
        for (int x = origin.getX() - searchRadius; x <= origin.getX() + searchRadius - footprintX; x++) {
            for (int z = origin.getZ() - searchRadius; z <= origin.getZ() + searchRadius - footprintZ; z++) {
                OptionalInt maybeY = standableY(world, x, z, originSurface);
                if (maybeY.isEmpty()) {
                    continue;
                }
                int y = maybeY.getAsInt();
                BlockPos anchor = new BlockPos(x, y, z);
                if (Math.abs(y - originSurface) > ySpread) {
                    continue;
                }
                double score = flatnessScore(world, anchor, footprintX, footprintZ, maxRange);
                if (score > scoreCap) {
                    continue;
                }
                if (!hasUsableStand(world, anchor, footprintX, footprintZ)) {
                    continue;
                }
                double distancePenalty = anchor.getSquaredDistance(origin) / 256.0D;
                double total = score + distancePenalty;
                if (total < bestScore) {
                    bestScore = total;
                    best = anchor.toImmutable();
                }
            }
        }
        if (best == null) {
            // 诊断 no_flat_site 拒因分类:对每个可站候选 footprint 跑 flatnessScore 同款检查,统计首个拒因——
            // okFlat=本可接受(平且干净,若>0 说明 best=null 另有缘故)/obstruct=feet或头非空气(草花雪等可清障碍,
            // flatten CLEAR 本就清→可放宽)/fluid=ground或feet带水(水域,更难)/groundAir=悬空。据此定修向。
            int okFlat = 0, obstruct = 0, fluid = 0, groundAir = 0, tooSteep = 0;
            for (int x = origin.getX() - searchRadius; x <= origin.getX() + searchRadius - footprintX; x++) {
                for (int z = origin.getZ() - searchRadius; z <= origin.getZ() + searchRadius - footprintZ; z++) {
                    OptionalInt my = standableY(world, x, z, originSurface);
                    if (my.isEmpty() || Math.abs(my.getAsInt() - originSurface) > ySpread) {
                        continue;
                    }
                    switch (footprintReject(world, new BlockPos(x, my.getAsInt(), z), footprintX, footprintZ, maxRange)) {
                        case 0 -> okFlat++;
                        case 1 -> obstruct++;
                        case 2 -> fluid++;
                        case 3 -> groundAir++;
                        case 4 -> tooSteep++;
                        default -> { }
                    }
                }
            }
            BotLog.action(bot, "no_flat_site_diag",
                    "okFlat", okFlat, "obstruct", obstruct, "fluid", fluid,
                    "groundAir", groundAir, "tooSteep", tooSteep,
                    "maxRange", maxRange, "ySpread", ySpread, "searchRadius", searchRadius);
        }
        return Optional.ofNullable(best);
    }

    // 诊断用:对 footprint 跑 flatnessScore 同款逐列检查,返回【首个】拒因码——
    // 0=干净可接受 / 1=feet或头非空气(可清障碍) / 2=ground或feet带流体(水) / 3=ground悬空 / 4=落差>maxRange / -1=不可站。
    private static int footprintReject(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ, int maxRange) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                OptionalInt my = standableY(world, x, z, anchor.getY());
                if (my.isEmpty()) {
                    return -1;
                }
                int surfaceY = my.getAsInt();
                BlockPos feet = new BlockPos(x, surfaceY, z);
                if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    return 1; // 有碰撞箱的实心障碍(放宽后草等无碰撞植被不再算障碍)
                }
                BlockPos ground = feet.down();
                if (!world.getFluidState(ground).isEmpty() || !world.getFluidState(feet).isEmpty()) {
                    return 2;
                }
                if (world.getBlockState(ground).isAir()) {
                    return 3;
                }
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
            }
        }
        return (maxY - minY) > maxRange ? 4 : 0;
    }

    public static double flatnessScore(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ) {
        return flatnessScore(world, anchor, footprintX, footprintZ, 2);
    }

    public static double flatnessScore(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ, int maxRange) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double sum = 0.0D;
        int count = 0;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                OptionalInt maybeY = standableY(world, x, z, anchor.getY());
                if (maybeY.isEmpty()) {
                    return Double.MAX_VALUE;
                }
                int surfaceY = maybeY.getAsInt();
                BlockPos feet = new BlockPos(x, surfaceY, z);
                // 放宽:接受 feet/头是"可清植被"(草/花/蕨/雪层等无碰撞箱方块)——bot 能站进去(isStandable 同款
                // 碰撞箱空判据),flatten 的 CLEAR 阶段也会把它们清掉。原"必须严格空气"把大量平整草地误判成
                // no_flat_site(实测 8888/31337/7777777 obstruct=700+、地形 spread=0 平的、fluid=0 干的)。
                // 实心障碍(有碰撞箱)仍拒(站不进去需预清);水由下面 fluid 检查拒(不接受水面站点)。
                if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    return Double.MAX_VALUE;
                }
                BlockPos ground = feet.down();
                FluidState groundFluid = world.getFluidState(ground);
                FluidState feetFluid = world.getFluidState(feet);
                if (!groundFluid.isEmpty() || !feetFluid.isEmpty() || world.getBlockState(ground).isAir()) {
                    return Double.MAX_VALUE;
                }
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                sum += surfaceY;
                count++;
            }
        }
        if (count == 0 || maxY - minY > maxRange) {
            return Double.MAX_VALUE;
        }
        double mean = sum / count;
        double variance = 0.0D;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int surfaceY = standableY(world, anchor.getX() + dx, anchor.getZ() + dz, anchor.getY()).orElse(anchor.getY());
                double delta = surfaceY - mean;
                variance += delta * delta;
            }
        }
        return variance / count + (maxY - minY);
    }

    private static boolean hasUsableStand(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ) {
        Standability.clearCache();
        for (int dx = -1; dx <= footprintX; dx++) {
            if (standableSurface(world, anchor.getX() + dx, anchor.getZ() - 1)
                    || standableSurface(world, anchor.getX() + dx, anchor.getZ() + footprintZ)) {
                return true;
            }
        }
        for (int dz = 0; dz < footprintZ; dz++) {
            if (standableSurface(world, anchor.getX() - 1, anchor.getZ() + dz)
                    || standableSurface(world, anchor.getX() + footprintX, anchor.getZ() + dz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean standableSurface(ServerWorld world, int x, int z) {
        return standableY(world, x, z, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)).isPresent();
    }

    private static OptionalInt standableY(ServerWorld world, int x, int z, int preferredY) {
        int heightmapY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        OptionalInt direct = firstStandable(world, x, z, preferredY, heightmapY, heightmapY + 1, heightmapY - 1);
        if (direct.isPresent()) {
            return direct;
        }
        int minY = Math.max(world.getBottomY() + 1, Math.min(preferredY, heightmapY) - 4);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 2, Math.max(preferredY, heightmapY) + 4);
        for (int y = minY; y <= maxY; y++) {
            if (Standability.isStandable(world, new BlockPos(x, y, z))) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt firstStandable(ServerWorld world, int x, int z, int... ys) {
        for (int y : ys) {
            if (y > world.getBottomY() && y < world.getBottomY() + world.getHeight() - 1
                    && Standability.isStandable(world, new BlockPos(x, y, z))) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }
}
