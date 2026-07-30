package io.github.zoyluo.aibot.mode;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mixin.ServerEntityManagerCacheAccessorMixin;
import io.github.zoyluo.aibot.mixin.ServerWorldEntityManagerAccessorMixin;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;

/** Validated adjacent fake-client step; distinct from privileged long-distance teleportation. */
public final class FakePlayerMotion {
    private FakePlayerMotion() {
    }

    public static boolean stepTo(AIPlayerEntity bot, BlockPos target, String reason) {
        BlockPos from = bot.getBlockPos();
        int dx = Math.abs(target.getX() - from.getX());
        int dy = Math.abs(target.getY() - from.getY());
        int dz = Math.abs(target.getZ() - from.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        if (dx > 1 || dy > 1 || dz > 1 || changedAxes == 0 || changedAxes > 2) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", reason, "from", from, "to", target);
            return false;
        }
        var world = bot.getServerWorld();
        if (!world.getBlockState(target).getCollisionShape(world, target).isEmpty()
                || !world.getBlockState(target.up()).getCollisionShape(world, target.up()).isEmpty()) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "blocked:" + reason,
                    "from", from, "to", target);
            return false;
        }
        double targetX = target.getX() + 0.5D;
        double targetY = target.getY();
        double targetZ = target.getZ() + 0.5D;
        Box landingBox = bot.getBoundingBox().offset(
                targetX - bot.getX(), targetY - bot.getY(), targetZ - bot.getZ());
        if (!world.isSpaceEmpty(bot, landingBox)) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "occupied:" + reason,
                    "from", from, "to", target);
            return false;
        }
        if (hasLandingEntityCollision(bot, landingBox)) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "entity_occupied:" + reason,
                    "from", from, "to", target);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.teleport(world, targetX, targetY, targetZ,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        BotLog.action(bot, "fake_player_step", "reason", reason, "from", from, "to", target);
        return true;
    }

    /** Adjacent step whose destination must already be a dry, supported player landing. */
    public static boolean stepToStandable(AIPlayerEntity bot, BlockPos target, String reason) {
        Standability.clearCache();
        if (!Standability.isStandable(bot.getServerWorld(), target)) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "no_landing:" + reason,
                    "from", bot.getBlockPos(), "to", target);
            return false;
        }
        if (!stepTo(bot, target, reason)) {
            return false;
        }
        // ServerPlayerEntity normally learns this from the client's movement packet. A fake
        // player has no client, so a collision-validated landing must publish the same grounded
        // state explicitly; otherwise pickup/checkpoint code can remain suspended forever even
        // though there is a solid support directly below its feet.
        bot.setOnGround(true);
        return true;
    }

    /**
     * Adjacent fake-client swim step that keeps the player inside the reached water cell until the
     * next server tick. Landing exactly on the integer Y boundary lets gravity floor the clientless
     * player back into the previous block before the next rescue tick, producing a permanent
     * two-cell oscillation in vertical water shafts.
     */
    public static boolean swimStepTo(AIPlayerEntity bot, BlockPos target, String reason) {
        var world = bot.getServerWorld();
        if (!world.getFluidState(target).isIn(FluidTags.WATER)
                && !world.getFluidState(target.up()).isIn(FluidTags.WATER)) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "not_water:" + reason,
                    "from", bot.getBlockPos(), "to", target);
            return false;
        }
        if (!stepTo(bot, target, reason)) {
            return false;
        }
        // A 0.125-block inset is enough to survive one gravity tick while keeping the normal
        // 1.8-block player box inside the already-validated feet/head column.
        Box inset = bot.getBoundingBox().offset(0.0D, 0.125D, 0.0D);
        if (world.isSpaceEmpty(bot, inset)) {
            bot.teleport(world, target.getX() + 0.5D, target.getY() + 0.125D,
                    target.getZ() + 0.5D, Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        }
        bot.setVelocity(Vec3d.ZERO);
        bot.fallDistance = 0.0F;
        BotLog.action(bot, "fake_player_swim_step", "reason", reason, "to", target);
        return bot.getBlockPos().equals(target);
    }

    /**
     * Moves a grounded fake player just over one edge of its current support while sneaking.
     *
     * <p>The 0.55-block shift leaves part of the player collision box over the original support,
     * but exposes that support's vertical face to an ordinary placement ray. This is the physical
     * equivalent of a player sneak-bridging one foundation block; callers must return to the center
     * in the same tick with {@link #returnToBlockCenter}.</p>
     */
    public static boolean shiftToSupportEdge(AIPlayerEntity bot,
                                             BlockPos anchorFeet,
                                             Direction direction,
                                             String reason) {
        if (direction.getAxis().isVertical()
                || !bot.getBlockPos().equals(anchorFeet)
                || !bot.isOnGround()) {
            BotLog.action(bot, "fake_player_edge_shift_rejected", "reason", "invalid_origin:" + reason,
                    "from", bot.getBlockPos(), "anchor", anchorFeet, "direction", direction);
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos support = anchorFeet.down();
        var supportState = world.getBlockState(support);
        if (supportState.getCollisionShape(world, support).isEmpty()
                || Standability.isDangerous(supportState)) {
            BotLog.action(bot, "fake_player_edge_shift_rejected", "reason", "unsafe_support:" + reason,
                    "from", bot.getBlockPos(), "support", support);
            return false;
        }
        double dx = direction.getOffsetX() * 0.55D;
        double dz = direction.getOffsetZ() * 0.55D;
        var shiftedBox = bot.getBoundingBox().offset(dx, 0.0D, dz);
        double overlapX = Math.min(shiftedBox.maxX, support.getX() + 1.0D)
                - Math.max(shiftedBox.minX, support.getX());
        double overlapZ = Math.min(shiftedBox.maxZ, support.getZ() + 1.0D)
                - Math.max(shiftedBox.minZ, support.getZ());
        if (overlapX <= 0.0D || overlapZ <= 0.0D || !world.isSpaceEmpty(bot, shiftedBox)) {
            BotLog.action(bot, "fake_player_edge_shift_rejected", "reason", "blocked_edge:" + reason,
                    "from", bot.getBlockPos(), "direction", direction);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.getActionPack().setSneaking(true);
        bot.teleport(world,
                anchorFeet.getX() + 0.5D + dx,
                anchorFeet.getY(),
                anchorFeet.getZ() + 0.5D + dz,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        BotLog.action(bot, "fake_player_edge_shift", "reason", reason,
                "anchor", anchorFeet, "direction", direction);
        return true;
    }

    /** Returns a bounded edge shift to the exact center of its original supported cell. */
    public static boolean returnToBlockCenter(AIPlayerEntity bot, BlockPos anchorFeet, String reason) {
        var world = bot.getServerWorld();
        double centerX = anchorFeet.getX() + 0.5D;
        double centerZ = anchorFeet.getZ() + 0.5D;
        double dx = bot.getX() - centerX;
        double dz = bot.getZ() - centerZ;
        var support = world.getBlockState(anchorFeet.down());
        if (bot.getY() != anchorFeet.getY()
                || dx * dx + dz * dz > 0.75D * 0.75D
                || support.getCollisionShape(world, anchorFeet.down()).isEmpty()
                || !world.getBlockState(anchorFeet).getCollisionShape(world, anchorFeet).isEmpty()
                || !world.getBlockState(anchorFeet.up()).getCollisionShape(world, anchorFeet.up()).isEmpty()) {
            bot.getActionPack().setSneaking(false);
            BotLog.action(bot, "fake_player_center_return_rejected", "reason", reason,
                    "from", bot.getBlockPos(), "anchor", anchorFeet);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.teleport(world, centerX, anchorFeet.getY(), centerZ,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        // stopMovement() only releases the synthetic client inputs.  A completed walk can still
        // leave ordinary server-side horizontal velocity on the fake player; without clearing it,
        // the next entity tick can carry the player into the neighbouring cell immediately after
        // this exact-center return.  Callers use the returned cell as a physical transaction
        // anchor (bucket placement, shelter foundation and sealed disposal), so publish the same
        // stationary grounded state a real player establishes after stopping on solid support.
        bot.setVelocity(Vec3d.ZERO);
        bot.fallDistance = 0.0F;
        bot.setOnGround(true);
        bot.getActionPack().setSneaking(false);
        boolean returned = bot.getBlockPos().equals(anchorFeet);
        BotLog.action(bot, returned ? "fake_player_center_return" : "fake_player_center_return_rejected",
                "reason", reason, "anchor", anchorFeet);
        return returned;
    }

    /**
     * A bounded same-cell pickup nudge. The player stays inside its current supported cell and
     * never overlaps the neighbouring obstacle, but moves close enough for vanilla ItemEntity
     * pickup reach to cover an item resting on top of that obstacle.
     */
    public static boolean nudgeWithinBlockToward(AIPlayerEntity bot,
                                                 BlockPos anchorFeet,
                                                 Vec3d target,
                                                 String reason) {
        return nudgeWithinBlockToward(bot, anchorFeet, target, 0.15D, reason);
    }

    /** Same-cell pickup nudge with a caller-bounded offset for an already open adjacent cell. */
    public static boolean nudgeWithinBlockToward(AIPlayerEntity bot,
                                                 BlockPos anchorFeet,
                                                 Vec3d target,
                                                 double offset,
                                                 String reason) {
        if (!bot.getBlockPos().equals(anchorFeet) || !bot.isOnGround()) {
            return false;
        }
        var world = bot.getServerWorld();
        Standability.clearCache();
        if (!Standability.isStandable(world, anchorFeet)) {
            return false;
        }
        double centerX = anchorFeet.getX() + 0.5D;
        double centerZ = anchorFeet.getZ() + 0.5D;
        double vx = target.x - centerX;
        double vz = target.z - centerZ;
        double length = Math.sqrt(vx * vx + vz * vz);
        if (length < 1.0E-6D) {
            return false;
        }
        double boundedOffset = MathHelper.clamp(offset, 0.05D, 0.45D);
        double targetX = centerX + vx / length * boundedOffset;
        double targetZ = centerZ + vz / length * boundedOffset;
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        var shiftedBox = bot.getBoundingBox().offset(dx, 0.0D, dz);
        if (!world.isSpaceEmpty(bot, shiftedBox)) {
            BotLog.action(bot, "fake_player_pickup_nudge_rejected", "reason", reason,
                    "anchor", anchorFeet);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.getActionPack().setSneaking(true);
        bot.teleport(world, targetX, anchorFeet.getY(), targetZ,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        BotLog.action(bot, "fake_player_pickup_nudge", "reason", reason, "anchor", anchorFeet);
        return true;
    }

    /**
     * Emulates exactly one client-authored jump cell for a server-side fake player.
     *
     * <p>This is deliberately narrower than {@link #stepTo}: the bot must be grounded, the
     * destination must be exactly one block higher and at most one horizontal block away, and a
     * horizontal jump must already have a standable landing. A same-column jump is reserved for
     * the path executor's immediately verified vanilla pillar placement.</p>
     */
    public static boolean jumpTo(AIPlayerEntity bot, BlockPos target, String reason) {
        BlockPos from = bot.getBlockPos();
        var world = bot.getServerWorld();
        int dx = Math.abs(target.getX() - from.getX());
        int dy = target.getY() - from.getY();
        int dz = Math.abs(target.getZ() - from.getZ());
        boolean sameColumn = dx == 0 && dz == 0;
        // ServerPlayerEntity normally refreshes onGround from client movement packets. A
        // clientless fake player can therefore report false on the tick after a verified landing.
        // Accept that stale bit only when the current pose is dry/standable and Minecraft's own
        // one-microblock support probe finds the exact block below. A genuinely airborne entity,
        // even one only centimetres above a nominally standable cell, still fails this boundary.
        boolean verifiedSupportedPose = false;
        if (!bot.isOnGround()) {
            Standability.clearCache();
            Box box = bot.getBoundingBox();
            Box supportProbe = new Box(
                    box.minX, box.minY - 1.0E-6D, box.minZ,
                    box.maxX, box.minY, box.maxZ);
            verifiedSupportedPose = Standability.isStandable(world, from)
                    && world.findSupportingBlockPos(bot, supportProbe)
                    .filter(from.down()::equals)
                    .isPresent();
        }
        if (dy != 1 || dx + dz > 1 || (!bot.isOnGround() && !verifiedSupportedPose)) {
            BotLog.action(bot, "fake_player_jump_rejected", "reason", reason,
                    "from", from, "to", target, "grounded", bot.isOnGround(),
                    "supported_pose", verifiedSupportedPose);
            return false;
        }
        if (!world.getBlockState(target).getCollisionShape(world, target).isEmpty()
                || !world.getBlockState(target.up()).getCollisionShape(world, target.up()).isEmpty()
                || Standability.isDangerous(world.getBlockState(target))
                || Standability.isDangerous(world.getBlockState(target.up()))) {
            BotLog.action(bot, "fake_player_jump_rejected", "reason", "blocked:" + reason,
                    "from", from, "to", target);
            return false;
        }
        if (!sameColumn && !Standability.isStandable(world, target)) {
            BotLog.action(bot, "fake_player_jump_rejected", "reason", "no_landing:" + reason,
                    "from", from, "to", target);
            return false;
        }
        if (world.getBlockState(from.down()).getCollisionShape(world, from.down()).isEmpty()) {
            BotLog.action(bot, "fake_player_jump_rejected", "reason", "no_takeoff_support:" + reason,
                    "from", from, "to", target);
            return false;
        }
        double targetX = target.getX() + 0.5D;
        double targetY = target.getY();
        double targetZ = target.getZ() + 0.5D;
        Box landingBox = bot.getBoundingBox().offset(
                targetX - bot.getX(), targetY - bot.getY(), targetZ - bot.getZ());
        if (!world.isSpaceEmpty(bot, landingBox)) {
            BotLog.action(bot, "fake_player_jump_rejected", "reason", "occupied:" + reason,
                    "from", from, "to", target);
            return false;
        }
        if (hasLandingEntityCollision(bot, landingBox)) {
            BotLog.action(bot, "fake_player_jump_rejected", "reason", "entity_occupied:" + reason,
                    "from", from, "to", target);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.teleport(world, targetX, targetY, targetZ,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        // A horizontal jump has already been collision-checked against a real landing. A normal
        // client reports that landing in its next movement packet, but this fake player has no
        // client and may otherwise remain airborne until an unrelated server tick repairs the
        // flag. Publish only the verified natural landing here. Same-column jumps are still
        // suspended until their caller places and verifies the pillar support.
        if (!sameColumn) {
            bot.setOnGround(true);
        }
        BotLog.action(bot, "fake_player_jump", "reason", reason, "from", from, "to", target);
        return true;
    }

    /**
     * Checks the raw section cache instead of the ordinary world query. A newly loaded section can
     * remain HIDDEN for the rest of its spawn tick; vanilla spatial queries intentionally skip that
     * section even though {@link net.minecraft.server.world.ServerEntityManager#addEntity} has
     * already inserted its entities into the cache. Reading only the few sections intersecting the
     * landing closes that one-tick collision blind spot without globally scanning world entities or
     * rejecting an otherwise valid physical step.
     */
    private static boolean hasLandingEntityCollision(AIPlayerEntity bot, Box landingBox) {
        var world = bot.getServerWorld();
        var manager = ((ServerWorldEntityManagerAccessorMixin) (Object) world)
                .aibot$getEntityManager();
        var cache = ((ServerEntityManagerCacheAccessorMixin) (Object) manager)
                .aibot$getSectionCache();
        int minSectionX = ChunkSectionPos.getSectionCoord(landingBox.minX - 2.0D);
        int minSectionY = ChunkSectionPos.getSectionCoord(landingBox.minY - 4.0D);
        int minSectionZ = ChunkSectionPos.getSectionCoord(landingBox.minZ - 2.0D);
        int maxSectionX = ChunkSectionPos.getSectionCoord(landingBox.maxX + 2.0D);
        int maxSectionY = ChunkSectionPos.getSectionCoord(landingBox.maxY);
        int maxSectionZ = ChunkSectionPos.getSectionCoord(landingBox.maxZ + 2.0D);
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    var section = cache.findTrackingSection(
                            ChunkSectionPos.asLong(sectionX, sectionY, sectionZ));
                    if (section == null || section.isEmpty()) {
                        continue;
                    }
                    boolean occupied = section.stream().anyMatch(entity ->
                            entity != bot
                                    && !entity.isRemoved()
                                    && landingBox.intersects(entity.getBoundingBox())
                                    && (entity instanceof LivingEntity living && living.isAlive()
                                    || EntityPredicates.CAN_COLLIDE.test(entity)));
                    if (occupied) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
