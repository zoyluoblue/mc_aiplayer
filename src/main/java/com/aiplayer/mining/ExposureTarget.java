package com.aiplayer.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;

public record ExposureTarget(
    BlockPos orePos,
    BlockPos exposurePos,
    boolean alreadyExposed,
    boolean valid,
    String reason
) {
    public ExposureTarget {
        orePos = orePos == null ? null : orePos.immutable();
        exposurePos = exposurePos == null ? null : exposurePos.immutable();
        reason = reason == null || reason.isBlank() ? "unknown" : reason;
    }

    public static ExposureTarget create(Level level, BlockPos currentPos, BlockPos orePos) {
        if (level == null || orePos == null) {
            return new ExposureTarget(orePos, null, false, false, "missing_ore");
        }
        return Direction.stream()
            .map(orePos::relative)
            .map(BlockPos::immutable)
            .filter(pos -> level.hasChunkAt(pos))
            .filter(pos -> isUsableExposureBlock(level, pos))
            .min(Comparator
                .comparingInt((BlockPos pos) -> exposurePriority(level, pos))
                .thenComparingInt(pos -> Math.abs(pos.getY() - currentPos.getY()))
                .thenComparingDouble(pos -> pos.distSqr(currentPos)))
            .map(pos -> new ExposureTarget(orePos, pos, level.getBlockState(pos).isAir(), true,
                level.getBlockState(pos).isAir() ? "existing_air_neighbor" : "dig_neighbor_to_expose"))
            .orElseGet(() -> fallbackNeighbor(level, currentPos, orePos));
    }

    public BlockPos routeTarget() {
        return alreadyExposed || exposurePos == null ? orePos : exposurePos;
    }

    public String toLogText() {
        return "orePos=" + (orePos == null ? "none" : orePos.toShortString())
            + ", exposurePos=" + (exposurePos == null ? "none" : exposurePos.toShortString())
            + ", routeTarget=" + (routeTarget() == null ? "none" : routeTarget().toShortString())
            + ", alreadyExposed=" + alreadyExposed
            + ", valid=" + valid
            + ", reason=" + reason;
    }

    private static boolean isUsableExposureBlock(Level level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        return level.getBlockState(pos).isAir()
            || (block != Blocks.BEDROCK
                && block != Blocks.LAVA
                && block != Blocks.WATER
                && block != Blocks.GRAVEL
                && block != Blocks.SAND
                && block != Blocks.RED_SAND);
    }

    private static int exposurePriority(Level level, BlockPos pos) {
        if (level.getBlockState(pos).isAir()) {
            return 0;
        }
        if (level.getBlockState(pos.below()).isAir()) {
            return 2;
        }
        return 1;
    }

    private static ExposureTarget fallbackNeighbor(Level level, BlockPos currentPos, BlockPos orePos) {
        return Direction.stream()
            .map(orePos::relative)
            .map(BlockPos::immutable)
            .filter(level::hasChunkAt)
            .min(Comparator
                .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - currentPos.getY()))
                .thenComparingDouble(pos -> pos.distSqr(currentPos)))
            .map(pos -> new ExposureTarget(orePos, pos, false, true, "fallback_neighbor_to_expose"))
            .orElseGet(() -> new ExposureTarget(orePos, null, false, false, "no_loaded_exposure_neighbor"));
    }
}
