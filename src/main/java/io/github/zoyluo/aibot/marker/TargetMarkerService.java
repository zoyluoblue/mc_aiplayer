package io.github.zoyluo.aibot.marker;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One non-persistent, owner-scoped spatial marker per player. */
public final class TargetMarkerService {
    public static final TargetMarkerService INSTANCE = new TargetMarkerService();
    public static final double MAX_DISTANCE = 256.0D;

    private final Map<UUID, Marker> markers = new ConcurrentHashMap<>();

    private TargetMarkerService() {
    }

    public Marker set(ServerPlayerEntity player, BlockPos clickedBlock, Direction face) {
        ServerWorld world = player.getServerWorld();
        BlockPos clicked = clickedBlock.toImmutable();
        if (player.getEyePos().squaredDistanceTo(clicked.toCenterPos())
                > (MAX_DISTANCE + 1.0D) * (MAX_DISTANCE + 1.0D)) {
            throw new IllegalArgumentException("marker_too_far: max=" + (int) MAX_DISTANCE);
        }
        if (!world.getWorldBorder().contains(clicked)) {
            throw new IllegalArgumentException("marker_outside_world_border");
        }
        if (!world.isChunkLoaded(clicked)) {
            throw new IllegalArgumentException("marker_chunk_not_loaded");
        }
        if (world.getBlockState(clicked).isAir()) {
            throw new IllegalArgumentException("marker_target_is_air");
        }

        BlockPos standPos = resolveStandPos(world, clicked, face);
        long tick = world.getServer().getTicks();
        Marker marker = new Marker(player.getServerWorld().getRegistryKey(), clicked, face, standPos, tick);
        markers.put(player.getUuid(), marker);
        return marker;
    }

    public Optional<Marker> get(UUID playerUuid) {
        return Optional.ofNullable(markers.get(playerUuid));
    }

    public Optional<Marker> forBot(AIPlayerEntity bot) {
        return AIPlayerManager.INSTANCE.ownerOf(bot).flatMap(this::get);
    }

    public void clear(UUID playerUuid) {
        markers.remove(playerUuid);
    }

    public void clearAll() {
        markers.clear();
    }

    private static BlockPos resolveStandPos(ServerWorld world, BlockPos clicked, Direction face) {
        BlockPos adjacent = clicked.offset(face);
        if (Standability.isStandable(world, adjacent)) {
            return adjacent.toImmutable();
        }
        BlockPos above = clicked.up();
        if (Standability.isStandable(world, above)) {
            return above.toImmutable();
        }
        return Standability.findNearestStandable(world, above, 3, 4, 4)
                .orElse(above)
                .toImmutable();
    }

    public record Marker(RegistryKey<World> dimension,
                         BlockPos clickedBlock,
                         Direction face,
                         BlockPos standPos,
                         long createdAtTick) {
        public String dimensionId() {
            return dimension.getValue().toString();
        }
    }
}
