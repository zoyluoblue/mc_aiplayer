package com.aiplayer.util;

import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class SurvivalInteractionValidator {
    public static final double DEFAULT_WORK_RANGE = 4.5D;

    private SurvivalInteractionValidator() {
    }

    public static ValidationResult canBreakBlock(AiPlayerEntity aiPlayer, BlockPos pos) {
        return canBreakBlock(aiPlayer, pos, DEFAULT_WORK_RANGE);
    }

    public static ValidationResult canBreakBlock(AiPlayerEntity aiPlayer, BlockPos pos, double range) {
        if (aiPlayer == null || pos == null) {
            return ValidationResult.invalid("missing_target");
        }
        if (!isWithinReach(aiPlayer, pos, range)) {
            return ValidationResult.invalid("out_of_reach");
        }
        if (!hasClearReachToBlock(aiPlayer, pos)) {
            return ValidationResult.invalid("blocked_line_of_sight");
        }
        return ValidationResult.ok();
    }

    public static PlacementResult canPlaceBlock(AiPlayerEntity aiPlayer, BlockPos pos) {
        if (aiPlayer == null || pos == null) {
            return PlacementResult.invalid("missing_target");
        }
        if (!aiPlayer.level().getBlockState(pos).isAir()) {
            return PlacementResult.invalid("target_not_air");
        }
        if (!isWithinReach(aiPlayer, pos, DEFAULT_WORK_RANGE)) {
            return PlacementResult.invalid("out_of_reach");
        }
        if (!hasClearReachToBlock(aiPlayer, pos)) {
            return PlacementResult.invalid("blocked_line_of_sight");
        }
        Optional<Direction> face = reachablePlacementFace(aiPlayer, pos, DEFAULT_WORK_RANGE);
        if (face.isEmpty()) {
            return PlacementResult.invalid("no_reachable_face");
        }
        return PlacementResult.ok(face.get());
    }

    public static Optional<Direction> reachablePlacementFace(AiPlayerEntity aiPlayer, BlockPos pos, double range) {
        if (aiPlayer == null || pos == null) {
            return Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            Block neighbor = aiPlayer.level().getBlockState(neighborPos).getBlock();
            if (neighbor == Blocks.AIR || neighbor == Blocks.WATER || neighbor == Blocks.LAVA || neighbor == Blocks.BEDROCK) {
                continue;
            }
            if (isWithinReach(aiPlayer, neighborPos, range) && hasClearReachToBlock(aiPlayer, neighborPos)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    public static boolean isWithinReach(AiPlayerEntity aiPlayer, BlockPos pos, double range) {
        return aiPlayer != null
            && pos != null
            && aiPlayer.position().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
    }

    public static boolean hasClearReachToBlock(AiPlayerEntity aiPlayer, BlockPos pos) {
        if (aiPlayer == null || pos == null) {
            return false;
        }
        return hasClearReachFrom(aiPlayer, aiPlayer.getEyePosition(), pos);
    }

    public static Optional<BlockPos> blockingBlockForBreak(AiPlayerEntity aiPlayer, BlockPos pos) {
        if (aiPlayer == null || pos == null) {
            return Optional.empty();
        }
        BlockHitResult hit = reachRaycast(aiPlayer, aiPlayer.getEyePosition(), pos);
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos)) {
            return Optional.empty();
        }
        return Optional.of(hit.getBlockPos().immutable());
    }

    public static boolean hasClearReachFrom(AiPlayerEntity aiPlayer, Vec3 eye, BlockPos pos) {
        if (aiPlayer == null || eye == null || pos == null) {
            return false;
        }
        BlockHitResult hit = reachRaycast(aiPlayer, eye, pos);
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private static BlockHitResult reachRaycast(AiPlayerEntity aiPlayer, Vec3 eye, BlockPos pos) {
        Vec3 target = Vec3.atCenterOf(pos);
        return aiPlayer.level().clip(new ClipContext(
            eye,
            target,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            aiPlayer
        ));
    }

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, "valid");
        }

        static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason == null || reason.isBlank() ? "invalid" : reason);
        }
    }

    public record PlacementResult(boolean valid, String reason, Direction face) {
        static PlacementResult ok(Direction face) {
            return new PlacementResult(true, "valid", face);
        }

        static PlacementResult invalid(String reason) {
            return new PlacementResult(false, reason == null || reason.isBlank() ? "invalid" : reason, null);
        }
    }
}
