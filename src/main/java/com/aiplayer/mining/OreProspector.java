package com.aiplayer.mining;

import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class OreProspector {
    private OreProspector() {
    }

    public static OreProspectResult scan(
        AiPlayerEntity aiPlayer,
        OreProspectTarget target,
        Set<BlockPos> rejectedTargets,
        int horizontalRadius,
        int verticalRadius,
        int maxBlocks
    ) {
        if (aiPlayer == null || target == null) {
            return OreProspectResult.notFound(target, false, "探矿目标为空", 0, 0, 0, 0, Map.of());
        }
        Level level = aiPlayer.level();
        String dimension = level.dimension().location().toString();
        if (!target.allowsDimension(dimension)) {
            return OreProspectResult.notFound(target, false,
                "目标需要在 " + target.dimension() + "，当前维度是 " + dimension,
                0, 0, 0, 0, Map.of("wrong_dimension", 1));
        }

        Set<Block> targetBlocks = targetBlocks(target);
        if (targetBlocks.isEmpty()) {
            return OreProspectResult.notFound(target, false, "探矿目标没有有效方块", 0, 0, 0, 0, Map.of("invalid_target_blocks", 1));
        }

        BlockPos center = aiPlayer.blockPosition();
        int safeHorizontalRadius = Math.max(1, Math.min(100, horizontalRadius));
        int safeVerticalRadius = Math.max(1, Math.min(100, verticalRadius));
        int levelMinY = level.getMinY();
        int levelMaxY = level.getMinY() + level.getHeight() - 1;
        int minY = Math.max(levelMinY, Math.max(center.getY() - safeVerticalRadius, target.scanMinY()));
        int maxY = Math.min(levelMaxY, Math.min(center.getY() + safeVerticalRadius, target.scanMaxY()));
        int budget = Math.max(1, maxBlocks);
        Set<BlockPos> rejected = rejectedTargets == null ? Set.of() : rejectedTargets;
        Set<Long> scannedChunks = new HashSet<>();
        Map<String, Integer> rejectionReasons = new TreeMap<>();
        Map<String, Integer> classificationCounts = new TreeMap<>();

        BlockPos best = null;
        OreProspectClassification bestClassification = OreProspectClassification.NOT_FOUND;
        OreTargetScore bestScore = null;
        double bestDistance = Double.MAX_VALUE;
        int scannedBlocks = 0;
        int candidates = 0;
        int rejectedCount = 0;
        boolean partial = false;

        for (int x = center.getX() - safeHorizontalRadius; x <= center.getX() + safeHorizontalRadius; x++) {
            for (int z = center.getZ() - safeHorizontalRadius; z <= center.getZ() + safeHorizontalRadius; z++) {
                int dx = x - center.getX();
                int dz = z - center.getZ();
                if (dx * dx + dz * dz > safeHorizontalRadius * safeHorizontalRadius) {
                    continue;
                }
                BlockPos column = new BlockPos(x, center.getY(), z);
                if (!level.hasChunkAt(column)) {
                    rejectedCount++;
                    merge(rejectionReasons, "chunk_not_loaded");
                    continue;
                }
                scannedChunks.add(chunkKey(x >> 4, z >> 4));
                for (int y = minY; y <= maxY; y++) {
                    if (scannedBlocks >= budget) {
                        partial = true;
                        return result(target, best, partial, "探矿达到本次扫描预算", scannedBlocks, scannedChunks.size(),
                            candidates, rejectedCount, bestDistance, center, bestClassification, bestScore, classificationCounts, rejectionReasons);
                    }
                    scannedBlocks++;
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (!targetBlocks.contains(block)) {
                        continue;
                    }
                    candidates++;
                    if (rejected.contains(pos)) {
                        rejectedCount++;
                        mergeClassification(classificationCounts, OreProspectClassification.REJECTED);
                        merge(rejectionReasons, "previously_rejected");
                        continue;
                    }
                    OreProspectClassification classification = classify(aiPlayer, pos);
                    mergeClassification(classificationCounts, classification);
                    if (classification == OreProspectClassification.REJECTED) {
                        rejectedCount++;
                        merge(rejectionReasons, "exposed_unreachable");
                        continue;
                    }
                    OreTargetScore score = OreTargetScore.calculate(target, center, pos, classification);
                    double distance = pos.distSqr(center);
                    if (score.betterThan(bestScore)) {
                        bestDistance = distance;
                        best = pos.immutable();
                        bestClassification = classification;
                        bestScore = score;
                    }
                }
            }
        }

        return result(target, best, partial, best == null ? "本轮探矿未发现目标方块" : "本轮探矿发现目标方块",
            scannedBlocks, scannedChunks.size(), candidates, rejectedCount, bestDistance, center, bestClassification,
            bestScore, classificationCounts, rejectionReasons);
    }

    private static OreProspectResult result(
        OreProspectTarget target,
        BlockPos best,
        boolean partial,
        String message,
        int scannedBlocks,
        int scannedChunks,
        int candidates,
        int rejected,
        double bestDistance,
        BlockPos center,
        OreProspectClassification classification,
        OreTargetScore selectedScore,
        Map<String, Integer> classificationCounts,
        Map<String, Integer> rejectionReasons
    ) {
        if (best == null) {
            return OreProspectResult.notFound(target, partial, message, scannedBlocks, scannedChunks, candidates, rejected,
                classificationCounts, rejectionReasons);
        }
        return OreProspectResult.found(target, best, partial, message, scannedBlocks, scannedChunks, candidates, rejected,
            bestDistance, best.getY() - center.getY(), classification, selectedScore, classificationCounts, rejectionReasons);
    }

    private static Set<Block> targetBlocks(OreProspectTarget target) {
        Set<Block> blocks = new HashSet<>();
        for (String blockId : target.blockIds()) {
            try {
                Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse(blockId));
                if (block != null && block != Blocks.AIR) {
                    blocks.add(block);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return blocks;
    }

    private static void merge(Map<String, Integer> map, String key) {
        map.merge(key == null || key.isBlank() ? "unknown" : key, 1, Integer::sum);
    }

    private static void mergeClassification(Map<String, Integer> map, OreProspectClassification classification) {
        map.merge((classification == null ? OreProspectClassification.NOT_FOUND : classification).name(), 1, Integer::sum);
    }

    private static OreProspectClassification classify(AiPlayerEntity aiPlayer, BlockPos pos) {
        boolean exposed = hasAirNeighbor(aiPlayer.level(), pos);
        if (!exposed) {
            return OreProspectClassification.EMBEDDED_HINT;
        }
        if (canWorkDirectly(aiPlayer, pos, 3.5D)) {
            return OreProspectClassification.DIRECT_MINEABLE;
        }
        if (hasReachableWorkStand(aiPlayer, pos)) {
            return OreProspectClassification.APPROACHABLE_EXPOSED;
        }
        return OreProspectClassification.REJECTED;
    }

    private static boolean canWorkDirectly(AiPlayerEntity aiPlayer, BlockPos pos, double range) {
        return aiPlayer.position().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
    }

    private static boolean hasAirNeighbor(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReachableWorkStand(AiPlayerEntity aiPlayer, BlockPos orePos) {
        for (Direction direction : Direction.values()) {
            BlockPos stand = orePos.relative(direction);
            if (!canStandAt(aiPlayer.level(), stand)) {
                continue;
            }
            if (Vec3.atCenterOf(stand).distanceToSqr(Vec3.atCenterOf(orePos)) > 4.5D * 4.5D) {
                continue;
            }
            if (aiPlayer.getNavigation().createPath(stand, 1) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean canStandAt(Level level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        Block headBlock = level.getBlockState(pos.above()).getBlock();
        Block support = level.getBlockState(pos.below()).getBlock();
        return block == Blocks.AIR
            && headBlock == Blocks.AIR
            && support != Blocks.AIR
            && support != Blocks.BEDROCK
            && support != Blocks.WATER
            && support != Blocks.LAVA;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }
}
