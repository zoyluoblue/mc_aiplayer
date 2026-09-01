package io.github.zoyluo.aibot.mode;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Strict-survival perception filter: nearby, exposed, and actually on the Bot's line of sight. */
public final class ObservableWorldQuery {
    private static final double FACE_ENDPOINT_DEPTH = 0.499D;
    private static final double FACE_SAMPLE_INSET = 0.375D;
    private static final double[][] FACE_SAMPLE_OFFSETS = {
            {0.0D, 0.0D},
            {-FACE_SAMPLE_INSET, 0.0D},
            {FACE_SAMPLE_INSET, 0.0D},
            {0.0D, -FACE_SAMPLE_INSET},
            {0.0D, FACE_SAMPLE_INSET},
            {-FACE_SAMPLE_INSET, -FACE_SAMPLE_INSET},
            {-FACE_SAMPLE_INSET, FACE_SAMPLE_INSET},
            {FACE_SAMPLE_INSET, -FACE_SAMPLE_INSET},
            {FACE_SAMPLE_INSET, FACE_SAMPLE_INSET}
    };

    private ObservableWorldQuery() {
    }

    public static boolean canObserveBlock(AIPlayerEntity bot, BlockPos pos) {
        return canObserveBlockWithin(bot, pos, 0);
    }

    /**
     * Prey-grounding observation at surface-search range: the specific cells under a visible
     * animal. Same fairness class as {@link #canObserveEntityWithin(AIPlayerEntity, Entity, int)}
     * (a player looking at a distant cow sees the ground it stands on): the per-face raycasts
     * and the strict capability bypass are unchanged, only the distance bound widens, and never
     * below the configured perception radius. Ordinary block scans keep the base radius.
     */
    public static boolean canObserveBlockWithin(AIPlayerEntity bot, BlockPos pos, int range) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_query").allowed()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            // Aim at the exposed face, not the block center. A center ray to distant flat ground
            // intersects a nearer ground block first and incorrectly reports the target as hidden.
            // Keep the endpoint just inside the target block. Stopping just outside the face
            // lets the ray end before entering the collision shape and produces MISS for an
            // otherwise visible floor block.
            var face = pos.toCenterPos().add(
                    direction.getOffsetX() * 0.499D,
                    direction.getOffsetY() * 0.499D,
                    direction.getOffsetZ() * 0.499D);
            if (canObserveFaceAfterPolicy(bot, pos, direction, face, range)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Explicit short-range observation for a block whose exposed area may not include any face
     * center. This is intentionally separate from {@link #canObserveBlock(AIPlayerEntity,
     * BlockPos)} so ordinary scans keep their six-ray cost. Callers opt into a deterministic 3x3
     * inset grid on each face and still need an exact fluid-aware world ray hit.
     */
    public static boolean canObserveBlockWithInsetFaces(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_inset_face_query").allowed()) {
            return true;
        }
        double observationRange = Math.min(
                Math.max(1, AIBotConfig.get().perception().radius()),
                bot.getBlockInteractionRange());
        double observationRangeSquared = observationRange * observationRange;
        Vec3d eye = bot.getEyePos();
        for (Direction direction : Direction.values()) {
            for (double[] offset : FACE_SAMPLE_OFFSETS) {
                Vec3d endpoint = insetFaceEndpoint(pos, direction, offset[0], offset[1]);
                if (eye.squaredDistanceTo(endpoint) > observationRangeSquared) {
                    continue;
                }
                BlockHitResult hit = bot.getServerWorld().raycast(new RaycastContext(
                        eye, endpoint,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.ANY,
                        bot));
                if (hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(pos)
                        && hit.getSide() == direction) {
                    return true;
                }
            }
        }
        return false;
    }

    static Vec3d insetFaceEndpoint(BlockPos pos,
                                   Direction face,
                                   double firstTangent,
                                   double secondTangent) {
        Vec3d center = pos.toCenterPos().add(
                face.getOffsetX() * FACE_ENDPOINT_DEPTH,
                face.getOffsetY() * FACE_ENDPOINT_DEPTH,
                face.getOffsetZ() * FACE_ENDPOINT_DEPTH);
        return switch (face.getAxis()) {
            case X -> center.add(0.0D, firstTangent, secondTangent);
            case Y -> center.add(firstTangent, 0.0D, secondTangent);
            case Z -> center.add(firstTangent, secondTangent, 0.0D);
        };
    }

    private static boolean canObserveFaceAfterPolicy(AIPlayerEntity bot,
                                                      BlockPos pos,
                                                      Direction face,
                                                      net.minecraft.util.math.Vec3d endpoint,
                                                      int range) {
        int radius = Math.max(Math.max(1, AIBotConfig.get().perception().radius()), range);
        if (bot.getEyePos().squaredDistanceTo(endpoint) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.getServerWorld().raycast(new RaycastContext(
                bot.getEyePos(), endpoint,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, bot));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos)
                && hit.getSide() == face;
    }

    /**
     * Returns whether the bot has an unobstructed view into a nearby world cell. Unlike
     * {@link #canObserveBlock(AIPlayerEntity, BlockPos)}, an empty/non-colliding target is a valid
     * result, so callers can gate feet/head reads before asking whether a position is standable.
     */
    public static boolean canObserveCell(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_cell_query").allowed()) {
            return true;
        }
        return canObserveCellWithinAfterPolicy(bot, pos, 0);
    }

    /**
     * Prey-grounding cell observation at surface-search range; see
     * {@link #canObserveBlockWithin(AIPlayerEntity, BlockPos, int)} for the fairness rationale.
     */
    public static boolean canObserveCellWithin(AIPlayerEntity bot, BlockPos pos, int range) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_cell_query").allowed()) {
            return true;
        }
        return canObserveCellWithinAfterPolicy(bot, pos, range);
    }

    private static boolean canObserveCellWithinAfterPolicy(AIPlayerEntity bot, BlockPos pos, int range) {
        int radius = Math.max(Math.max(1, AIBotConfig.get().perception().radius()), range);
        if (bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.getServerWorld().raycast(new RaycastContext(
                bot.getEyePos(), pos.toCenterPos(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, bot));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos));
    }

    public static boolean canObserveEntity(AIPlayerEntity bot, Entity entity) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, AIBotConfig.get().perception().radius());
        return bot.squaredDistanceTo(entity) <= (double) radius * radius && bot.canSee(entity);
    }

    /**
     * Live-fauna observation at surface-search range: a real player sees animals at render
     * distance whenever line of sight holds, far beyond the interaction-scale radius that
     * bounds block reads. The raycast stays, so terrain still hides herds; only the distance
     * bound widens, and never below the configured perception radius. Block queries are
     * unaffected, and the strict capability bypass stays exactly as it is.
     */
    public static boolean canObserveEntityWithin(AIPlayerEntity bot, Entity entity, int range) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        int radius = Math.max(Math.max(1, AIBotConfig.get().perception().radius()), range);
        return bot.squaredDistanceTo(entity) <= (double) radius * radius && bot.canSee(entity);
    }
}
