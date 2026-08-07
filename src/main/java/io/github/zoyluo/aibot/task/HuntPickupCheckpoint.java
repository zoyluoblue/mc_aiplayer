package io.github.zoyluo.aibot.task;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict, Minecraft-bootstrap-independent codec for one Hunt PICKUP transaction. */
public final class HuntPickupCheckpoint {
    public static final int SCHEMA = 1;
    public static final int MAX_BOUND_DROP_ENTRIES = 16;
    public static final int MAX_BOUND_DROP_UNITS = 64;
    public static final int RECOVERY_LIMIT_TICKS = 240;
    private static final int DROP_BIND_WINDOW = 40;
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Set<String> RAW_MEAT_IDS = Set.of(
            "minecraft:beef", "minecraft:porkchop", "minecraft:mutton",
            "minecraft:chicken", "minecraft:rabbit");
    private static final Set<String> KEYS = Set.of(
            "task_schema", "cursor_kind", "transaction_state",
            "target_count", "require_full_quota", "dimension", "expected_raw_item",
            "pickup_origin", "pickup_return_anchor", "inventory_baseline",
            "pickup_stat_baseline", "aux_inventory_baseline",
            "aux_pickup_stat_baseline", "pickup_started_world_time",
            "bound_drop_units");

    private HuntPickupCheckpoint() {
    }

    public enum State { OPEN, CLOSED_COLLECTED, CLOSED_NO_RAW }

    public record Position(int x, int y, int z) {
        String encode() {
            return x + "," + y + "," + z;
        }
    }

    public record Metadata(
            State transactionState,
            int targetCount,
            boolean requireFullQuota,
            String dimension,
            String expectedRawItemId,
            Position pickupOrigin,
            Position pickupReturnAnchor,
            int inventoryBaseline,
            int pickupStatBaseline,
            int auxInventoryBaseline,
            long auxPickupStatBaseline,
            long pickupStartedWorldTime,
            Map<UUID, Integer> boundDropUnits) {
        public Metadata {
            boundDropUnits = Map.copyOf(boundDropUnits);
        }

        public boolean open() {
            return transactionState == State.OPEN;
        }

        public int boundUnits() {
            return boundDropUnitCount(boundDropUnits);
        }
    }

    public static Map<String, String> encode(Metadata metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_schema", String.valueOf(SCHEMA));
        values.put("cursor_kind", "hunt_pickup");
        values.put("transaction_state", metadata.transactionState().name());
        values.put("target_count", String.valueOf(metadata.targetCount()));
        values.put("require_full_quota", String.valueOf(metadata.requireFullQuota()));
        values.put("dimension", metadata.dimension());
        values.put("expected_raw_item", metadata.expectedRawItemId());
        values.put("pickup_origin", metadata.pickupOrigin().encode());
        values.put("pickup_return_anchor", metadata.pickupReturnAnchor().encode());
        values.put("inventory_baseline", String.valueOf(metadata.inventoryBaseline()));
        values.put("pickup_stat_baseline", String.valueOf(metadata.pickupStatBaseline()));
        values.put("aux_inventory_baseline", String.valueOf(metadata.auxInventoryBaseline()));
        values.put("aux_pickup_stat_baseline",
                String.valueOf(metadata.auxPickupStatBaseline()));
        values.put("pickup_started_world_time",
                String.valueOf(metadata.pickupStartedWorldTime()));
        values.put("bound_drop_units", encodeBoundDropUnits(metadata.boundDropUnits()));
        Map<String, String> encoded = Map.copyOf(values);
        return inspect(encoded).isPresent() ? encoded : Map.of();
    }

    public static Optional<Metadata> inspect(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()
                || !checkpoint.keySet().equals(KEYS)) {
            return Optional.empty();
        }
        try {
            if (strictInt(required(checkpoint, "task_schema")) != SCHEMA
                    || !"hunt_pickup".equals(required(checkpoint, "cursor_kind"))) {
                return Optional.empty();
            }
            State state = State.valueOf(required(checkpoint, "transaction_state"));
            int target = strictInt(required(checkpoint, "target_count"));
            boolean fullQuota = strictBoolean(required(checkpoint, "require_full_quota"));
            String dimension = strictIdentifier(required(checkpoint, "dimension"));
            String item = strictIdentifier(required(checkpoint, "expected_raw_item"));
            Position origin = decodePosition(required(checkpoint, "pickup_origin")).orElse(null);
            Position anchor = decodePosition(
                    required(checkpoint, "pickup_return_anchor")).orElse(null);
            int inventory = strictInt(required(checkpoint, "inventory_baseline"));
            int pickupStat = strictInt(required(checkpoint, "pickup_stat_baseline"));
            int auxInventory = strictInt(required(checkpoint, "aux_inventory_baseline"));
            long auxStat = strictLong(required(checkpoint, "aux_pickup_stat_baseline"));
            long started = strictLong(required(checkpoint, "pickup_started_world_time"));
            Optional<Map<UUID, Integer>> units = decodeBoundDropUnits(
                    required(checkpoint, "bound_drop_units"));
            if (target < 1 || target > 4096 || !RAW_MEAT_IDS.contains(item)
                    || origin == null || anchor == null
                    || inventory < 0 || inventory > 4096 || pickupStat < 0
                    || auxInventory < 0 || auxInventory > 4096 || auxStat < 0L
                    || started < 0L || units.isEmpty()
                    || state == State.CLOSED_NO_RAW
                    && !units.orElseThrow().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Metadata(
                    state, target, fullQuota, dimension, item, origin, anchor,
                    inventory, pickupStat, auxInventory, auxStat, started,
                    units.orElseThrow()));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public static long ageAt(long startedWorldTime, long currentWorldTime) {
        return startedWorldTime < 0L || currentWorldTime < startedWorldTime
                ? -1L : currentWorldTime - startedWorldTime;
    }

    public static boolean canBindFreshDropAtAge(long age) {
        return age >= 0L && age <= DROP_BIND_WINDOW;
    }

    public static boolean collectionCoversBoundUnits(
            int inventoryBaseline, int currentInventory,
            int pickupStatBaseline, int currentPickupStat, int requiredUnits) {
        return requiredUnits > 0
                && ((long) currentInventory - inventoryBaseline >= requiredUnits
                || (long) currentPickupStat - pickupStatBaseline >= requiredUnits);
    }

    public static boolean bindDropUnits(
            Map<UUID, Integer> unitsById, UUID id, int units) {
        if (id == null || units <= 0 || units > MAX_BOUND_DROP_UNITS) {
            return false;
        }
        Integer existing = unitsById.get(id);
        if (existing != null) {
            return existing == units;
        }
        if (unitsById.size() >= MAX_BOUND_DROP_ENTRIES
                || (long) boundDropUnitCount(unitsById) + units > MAX_BOUND_DROP_UNITS) {
            return false;
        }
        unitsById.put(id, units);
        return true;
    }

    public static int boundDropUnitCount(Map<UUID, Integer> unitsById) {
        long total = 0L;
        for (Integer units : unitsById.values()) {
            if (units == null || units <= 0) {
                return Integer.MAX_VALUE;
            }
            total += units;
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private static String encodeBoundDropUnits(Map<UUID, Integer> unitsById) {
        if (unitsById.isEmpty()) {
            return "none";
        }
        return unitsById.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Optional<Map<UUID, Integer>> decodeBoundDropUnits(String encoded) {
        if ("none".equals(encoded)) {
            return Optional.of(Map.of());
        }
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        Map<UUID, Integer> decoded = new LinkedHashMap<>();
        for (String pair : encoded.split(";", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            UUID id = UUID.fromString(parts[0]);
            if (!id.toString().equals(parts[0])
                    || !bindDropUnits(decoded, id, strictInt(parts[1]))) {
                return Optional.empty();
            }
        }
        Map<UUID, Integer> result = Map.copyOf(decoded);
        return encodeBoundDropUnits(result).equals(encoded)
                ? Optional.of(result) : Optional.empty();
    }

    private static Optional<Position> decodePosition(String encoded) {
        String[] parts = encoded.split(",", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            Position position = new Position(
                    strictInt(parts[0]), strictInt(parts[1]), strictInt(parts[2]));
            return position.encode().equals(encoded)
                    ? Optional.of(position) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String strictIdentifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("non_canonical_identifier");
        }
        return value;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_" + key);
        }
        return value;
    }

    private static int strictInt(String value) {
        int parsed = Integer.parseInt(value);
        if (!String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non_canonical_int");
        }
        return parsed;
    }

    private static long strictLong(String value) {
        long parsed = Long.parseLong(value);
        if (!String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non_canonical_long");
        }
        return parsed;
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("non_canonical_boolean");
    }
}
