package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Durable square-spiral cursor for strict-survival lava prospecting.
 *
 * <p>The cursor contains only observed, factual state: the last physically reached work face and
 * the remaining length of the active branch leg. It never stores a hidden fluid location. A
 * restart can therefore return to {@link #face()} and resume the same branch without granting the
 * task information that a player did not observe.</p>
 */
record ObsidianSearchCursor(
        int schema,
        BlockPos origin,
        BlockPos face,
        int directionIndex,
        int legIndex,
        int stepsLeft,
        int legLength,
        int baseLegLength,
        int facesSinceScan,
        int topologyEpoch,
        int blockedDirections,
        int produced
) {
    static final int CURRENT_SCHEMA = 2;
    private static final int LEGACY_SCHEMA = 1;
    static final String KIND = "obsidian_search";
    private static final Direction[] DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    ObsidianSearchCursor {
        schema = schema <= 0 ? CURRENT_SCHEMA : schema;
        origin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
        face = face == null ? origin : face.toImmutable();
        directionIndex = directionIndex == -1 ? -1 : Math.floorMod(directionIndex, DIRECTIONS.length);
        legIndex = Math.max(0, legIndex);
        stepsLeft = Math.max(0, stepsLeft);
        baseLegLength = Math.max(1, baseLegLength);
        legLength = Math.max(baseLegLength, legLength);
        facesSinceScan = Math.max(0, facesSinceScan);
        topologyEpoch = Math.max(0, topologyEpoch);
        blockedDirections = Math.max(0, Math.min(0b1111, blockedDirections));
        produced = Math.max(0, produced);
    }

    static ObsidianSearchCursor initial(BlockPos origin, int baseLegLength) {
        BlockPos safeOrigin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
        int base = Math.max(1, baseLegLength);
        return new ObsidianSearchCursor(CURRENT_SCHEMA, safeOrigin, safeOrigin,
                -1, 0, 0, base, base, 0, 0, 0, 0);
    }

    Direction direction() {
        return directionIndex < 0 ? DIRECTIONS[0] : DIRECTIONS[directionIndex];
    }

    ObsidianSearchCursor beginNextLeg() {
        if (directionIndex < 0) {
            return new ObsidianSearchCursor(schema, origin, face, 0, 0,
                    baseLegLength, baseLegLength, baseLegLength,
                    facesSinceScan, topologyEpoch, blockedDirections, produced);
        }
        int nextLeg = legIndex + 1;
        int nextLength = legLength + (nextLeg % 2 == 0 ? baseLegLength : 0);
        return new ObsidianSearchCursor(schema, origin, face,
                (directionIndex + 1) % DIRECTIONS.length, nextLeg,
                nextLength, nextLength, baseLegLength,
                facesSinceScan, topologyEpoch, blockedDirections, produced);
    }

    BlockPos nextFace() {
        return face.offset(direction());
    }

    /** Advances only by physically measured motion along the active leg. */
    ObsidianSearchCursor advanceTo(BlockPos actualFace) {
        BlockPos actual = actualFace == null ? face : actualFace.toImmutable();
        Direction direction = direction();
        int dx = actual.getX() - face.getX();
        int dy = actual.getY() - face.getY();
        int dz = actual.getZ() - face.getZ();
        int forward = dx * direction.getOffsetX() + dz * direction.getOffsetZ();
        if (dy != 0
                || forward != 1
                || stepsLeft < 1
                || dx != direction.getOffsetX() * forward
                || dz != direction.getOffsetZ() * forward) {
            return this;
        }
        return new ObsidianSearchCursor(schema, origin, actual, directionIndex, legIndex,
                stepsLeft - forward, legLength, baseLegLength,
                facesSinceScan + forward, topologyEpoch + forward, 0, produced);
    }

    ObsidianSearchCursor markScanned() {
        return new ObsidianSearchCursor(schema, origin, face, directionIndex, legIndex,
                stepsLeft, legLength, baseLegLength, 0, topologyEpoch,
                blockedDirections, produced);
    }

    ObsidianSearchCursor topologyChanged() {
        return new ObsidianSearchCursor(schema, origin, face, directionIndex, legIndex,
                stepsLeft, legLength, baseLegLength, facesSinceScan, topologyEpoch + 1,
                0, produced);
    }

    ObsidianSearchCursor skipBlockedLeg() {
        int blocked = directionIndex < 0
                ? blockedDirections : blockedDirections | 1 << directionIndex;
        return new ObsidianSearchCursor(schema, origin, face, directionIndex, legIndex,
                0, legLength, baseLegLength, facesSinceScan, topologyEpoch,
                blocked, produced);
    }

    boolean blockedAllDirections() {
        return blockedDirections == 0b1111;
    }

    ObsidianSearchCursor withProduced(int total) {
        return new ObsidianSearchCursor(schema, origin, face, directionIndex, legIndex,
                stepsLeft, legLength, baseLegLength, facesSinceScan, topologyEpoch,
                blockedDirections, total);
    }

    Map<String, String> encode() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", String.valueOf(schema));
        values.put("cursor_kind", KIND);
        values.put("origin", encodePos(origin));
        values.put("work_face", encodePos(face));
        values.put("direction", String.valueOf(directionIndex));
        values.put("leg", String.valueOf(legIndex));
        values.put("steps_left", String.valueOf(stepsLeft));
        values.put("leg_length", String.valueOf(legLength));
        values.put("base_leg_length", String.valueOf(baseLegLength));
        values.put("faces_since_scan", String.valueOf(facesSinceScan));
        values.put("topology_epoch", String.valueOf(topologyEpoch));
        values.put("blocked_directions", String.valueOf(blockedDirections));
        values.put("produced", String.valueOf(produced));
        return Map.copyOf(values);
    }

    static Optional<ObsidianSearchCursor> decode(Map<String, String> values) {
        if (values == null || values.isEmpty() || !KIND.equals(values.get("cursor_kind"))) {
            return Optional.empty();
        }
        try {
            int schema = requiredInteger(values, "schema");
            if (schema != CURRENT_SCHEMA && schema != LEGACY_SCHEMA) {
                return Optional.empty();
            }
            BlockPos origin = decodePos(values.get("origin")).orElse(null);
            BlockPos face = decodePos(values.get("work_face")).orElse(null);
            if (origin == null || face == null) {
                return Optional.empty();
            }
            boolean required = schema == CURRENT_SCHEMA;
            int direction = integer(values, "direction", -1, required);
            int leg = integer(values, "leg", 0, required);
            int steps = integer(values, "steps_left", 0, required);
            int base = integer(values, "base_leg_length", 12, required);
            int legLength = integer(values, "leg_length", base, required);
            int faces = integer(values, "faces_since_scan", 0, required);
            int topology = integer(values, "topology_epoch", 0, required);
            int blocked = schema == LEGACY_SCHEMA
                    ? integer(values, "blocked_directions", 0, false)
                    : integer(values, "blocked_directions", 0, true);
            int produced = integer(values, "produced", 0, required);
            if (direction < -1 || direction >= DIRECTIONS.length
                    || leg < 0
                    || base < 1
                    || legLength < base
                    || steps < 0 || steps > legLength
                    || faces < 0
                    || topology < 0
                    || blocked < 0 || blocked > 0b1111
                    || produced < 0) {
                return Optional.empty();
            }
            return Optional.of(new ObsidianSearchCursor(
                    CURRENT_SCHEMA,
                    origin,
                    face,
                    direction,
                    leg,
                    steps,
                    legLength,
                    base,
                    faces,
                    topology,
                    blocked,
                    produced));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static int requiredInteger(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required cursor field " + key);
        }
        return Integer.parseInt(value);
    }

    private static int integer(Map<String, String> values, String key, int fallback,
                               boolean required) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("missing required cursor field " + key);
            }
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private static String encodePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split(",");
        if (parts.length != 3) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])));
    }
}

/** Rejects a target only until either the observed topology changes or a bounded TTL expires. */
final class ObsidianTargetMemory {
    private record Stamp(int topologyEpoch, int expiresAtTick) {
    }

    private final Map<BlockPos, Stamp> rejected = new HashMap<>();

    void reject(BlockPos pos, int topologyEpoch, int now, int ttl) {
        if (pos != null) {
            rejected.put(pos.toImmutable(),
                    new Stamp(Math.max(0, topologyEpoch), now + Math.max(1, ttl)));
        }
    }

    boolean isRejected(BlockPos pos, int topologyEpoch, int now) {
        Stamp stamp = rejected.get(pos);
        if (stamp == null) {
            return false;
        }
        if (stamp.topologyEpoch() != topologyEpoch || now >= stamp.expiresAtTick()) {
            rejected.remove(pos);
            return false;
        }
        return true;
    }

    void clear() {
        rejected.clear();
    }
}
