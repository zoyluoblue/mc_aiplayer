package io.github.zoyluo.aibot.mining;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Durable branch-mining cursor shared by mining batches and JVM restart recovery. */
public record MiningCursor(
        int schema,
        BlockPos origin,
        BlockPos face,
        int directionIndex,
        int legIndex,
        int stepsLeft,
        int legLength,
        int completedBatches
) {
    public static final int CURRENT_SCHEMA = 1;

    public MiningCursor {
        schema = schema <= 0 ? CURRENT_SCHEMA : schema;
        origin = origin == null ? null : origin.toImmutable();
        face = face == null ? null : face.toImmutable();
        // -1 is a semantic sentinel: the first branch leg has not started yet. Converting it to 3
        // makes a pre-first-tick restore take the "completed leg" path and incorrectly advance to leg 1.
        directionIndex = directionIndex == -1 ? -1 : Math.floorMod(directionIndex, 4);
        legIndex = Math.max(0, legIndex);
        stepsLeft = Math.max(0, stepsLeft);
        legLength = Math.max(1, legLength);
        completedBatches = Math.max(0, completedBatches);
    }

    public static MiningCursor initial(BlockPos origin, int baseLegLength) {
        BlockPos safeOrigin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
        return new MiningCursor(CURRENT_SCHEMA, safeOrigin, safeOrigin, -1, 0,
                0, Math.max(1, baseLegLength), 0);
    }

    public MiningCursor withFace(BlockPos newFace) {
        return new MiningCursor(schema, origin, newFace, directionIndex, legIndex,
                stepsLeft, legLength, completedBatches);
    }

    public MiningCursor withStrip(int newDirectionIndex, int newLegIndex,
                                  int newStepsLeft, int newLegLength) {
        return new MiningCursor(schema, origin, face, newDirectionIndex, newLegIndex,
                newStepsLeft, newLegLength, completedBatches);
    }

    public MiningCursor nextBatch(BlockPos newFace) {
        return new MiningCursor(schema, origin, newFace, directionIndex, legIndex,
                stepsLeft, legLength, completedBatches + 1);
    }

    public Map<String, String> encode() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", String.valueOf(schema));
        values.put("origin", encodePos(origin));
        values.put("face", encodePos(face));
        values.put("direction", String.valueOf(directionIndex));
        values.put("leg", String.valueOf(legIndex));
        values.put("steps_left", String.valueOf(stepsLeft));
        values.put("leg_length", String.valueOf(legLength));
        values.put("batches", String.valueOf(completedBatches));
        return Map.copyOf(values);
    }

    public static Optional<MiningCursor> decode(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            int schema = integer(values, "schema", CURRENT_SCHEMA);
            if (schema != CURRENT_SCHEMA) {
                return Optional.empty();
            }
            BlockPos origin = decodePos(values.get("origin")).orElse(null);
            BlockPos face = decodePos(values.get("face")).orElse(origin);
            if (origin == null || face == null) {
                return Optional.empty();
            }
            return Optional.of(new MiningCursor(
                    schema,
                    origin,
                    face,
                    integer(values, "direction", -1),
                    integer(values, "leg", 0),
                    integer(values, "steps_left", 0),
                    integer(values, "leg_length", 48),
                    integer(values, "batches", 0)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static String encodePos(BlockPos pos) {
        return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
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
