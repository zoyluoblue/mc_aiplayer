package io.github.zoyluo.aibot.task;

import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Mission-scoped search state shared by every {@link HuntTask} created for one goal.
 *
 * <p>The cursor deliberately records coarse, observed 32-by-32 sectors instead of exact prey
 * locations. Replanned hunt tasks can therefore continue exploring without forgetting old search
 * work or gaining information that the bot did not observe.</p>
 */
public final class HuntSearchCursor {
    public static final int CURRENT_SCHEMA = 1;
    public static final int SECTOR_SIZE = 32;
    public static final int MAX_VISITED_SECTORS = 2048;
    public static final String KIND = "hunt_search";

    private static final String SCHEMA_KEY = "schema";
    private static final String KIND_KEY = "cursor_kind";
    private static final String NEXT_ORDINAL_KEY = "next_ordinal";
    private static final String REVISION_KEY = "revision";
    private static final String VISITED_COUNT_KEY = "visited_count";
    private static final String SURFACE_ANCHOR_KEY = "surface_anchor";
    private static final String SECTOR_PREFIX = "sector.";
    private static final Set<String> BASE_KEYS = Set.of(
            SCHEMA_KEY,
            KIND_KEY,
            NEXT_ORDINAL_KEY,
            REVISION_KEY,
            VISITED_COUNT_KEY,
            SURFACE_ANCHOR_KEY);
    private static final Comparator<Sector> SECTOR_ORDER =
            Comparator.comparing(Sector::dimension)
                    .thenComparingInt(Sector::x)
                    .thenComparingInt(Sector::z);
    private static final Base64.Encoder DIMENSION_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DIMENSION_DECODER = Base64.getUrlDecoder();

    private long nextOrdinal;
    private long revision;
    private final LinkedHashSet<Sector> visitedSectors;
    private SurfaceAnchor surfaceAnchor;
    private boolean dirty;

    private HuntSearchCursor(long nextOrdinal, long revision,
                             Collection<Sector> visitedSectors,
                             SurfaceAnchor surfaceAnchor) {
        if (nextOrdinal < 0L || revision < 0L) {
            throw new IllegalArgumentException("cursor counters must be non-negative");
        }
        Objects.requireNonNull(visitedSectors, "visitedSectors");
        if (visitedSectors.size() > MAX_VISITED_SECTORS) {
            throw new IllegalArgumentException("too many visited hunt sectors");
        }
        this.nextOrdinal = nextOrdinal;
        this.revision = revision;
        this.visitedSectors = new LinkedHashSet<>(visitedSectors);
        if (this.visitedSectors.size() != visitedSectors.size()) {
            throw new IllegalArgumentException("duplicate visited hunt sectors");
        }
        this.surfaceAnchor = surfaceAnchor;
        this.dirty = false;
    }

    public static HuntSearchCursor initial() {
        return new HuntSearchCursor(0L, 0L, List.of(), null);
    }

    /**
     * Restores an encoded cursor. A null or empty map is the sole legacy representation and
     * restores a fresh cursor; every non-empty malformed representation fails closed.
     */
    public static Optional<HuntSearchCursor> decode(Map<String, String> encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Optional.of(initial());
        }
        try {
            Map<String, String> values = new LinkedHashMap<>(encoded);
            if (parseCanonicalInt(required(values, SCHEMA_KEY)) != CURRENT_SCHEMA
                    || !KIND.equals(required(values, KIND_KEY))) {
                return Optional.empty();
            }

            long nextOrdinal = parseCanonicalLong(required(values, NEXT_ORDINAL_KEY));
            long revision = parseCanonicalLong(required(values, REVISION_KEY));
            int visitedCount = parseCanonicalInt(required(values, VISITED_COUNT_KEY));
            if (nextOrdinal < 0L
                    || revision < 0L
                    || visitedCount < 0
                    || visitedCount > MAX_VISITED_SECTORS) {
                return Optional.empty();
            }

            Set<String> expectedKeys = new HashSet<>(BASE_KEYS);
            for (int index = 0; index < visitedCount; index++) {
                expectedKeys.add(sectorKey(index));
            }
            if (!values.keySet().equals(expectedKeys)) {
                return Optional.empty();
            }

            SurfaceAnchor anchor = decodeAnchor(required(values, SURFACE_ANCHOR_KEY))
                    .orElse(null);
            if (!required(values, SURFACE_ANCHOR_KEY).isEmpty() && anchor == null) {
                return Optional.empty();
            }

            List<Sector> sectors = new ArrayList<>(visitedCount);
            Sector previous = null;
            for (int index = 0; index < visitedCount; index++) {
                Sector sector = decodeSector(required(values, sectorKey(index))).orElse(null);
                if (sector == null || (previous != null
                        && SECTOR_ORDER.compare(previous, sector) >= 0)) {
                    return Optional.empty();
                }
                sectors.add(sector);
                previous = sector;
            }
            long factualRevision = Math.addExact(nextOrdinal, (long) visitedCount);
            if (anchor != null) {
                factualRevision = Math.addExact(factualRevision, 1L);
            }
            if (revision != factualRevision) {
                return Optional.empty();
            }
            return Optional.of(new HuntSearchCursor(nextOrdinal, revision, sectors, anchor));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public int schema() {
        return CURRENT_SCHEMA;
    }

    public synchronized long nextOrdinal() {
        return nextOrdinal;
    }

    public synchronized long revision() {
        return revision;
    }

    /**
     * Atomically consumes the persistence signal without changing the durable revision.
     */
    public synchronized boolean consumeDirty() {
        boolean changed = dirty;
        dirty = false;
        return changed;
    }

    /**
     * Claims one mission-global roam ordinal. The value is never reused by later hunt tasks.
     */
    public synchronized long claimNextOrdinal() {
        if (nextOrdinal == Long.MAX_VALUE) {
            throw new IllegalStateException("hunt search ordinal exhausted");
        }
        requireRevisionCapacity();
        long claimed = nextOrdinal++;
        changed();
        return claimed;
    }

    public synchronized int visitedCount() {
        return visitedSectors.size();
    }

    public synchronized boolean isFull() {
        return visitedSectors.size() >= MAX_VISITED_SECTORS;
    }

    public static Sector sectorFor(String dimension, int blockX, int blockZ) {
        return new Sector(
                dimension,
                Math.floorDiv(blockX, SECTOR_SIZE),
                Math.floorDiv(blockZ, SECTOR_SIZE));
    }

    public synchronized boolean contains(String dimension, int blockX, int blockZ) {
        return contains(sectorFor(dimension, blockX, blockZ));
    }

    public synchronized boolean contains(Sector sector) {
        return visitedSectors.contains(Objects.requireNonNull(sector, "sector"));
    }

    /**
     * Marks a sector once. At capacity the cursor refuses the new sector and retains every old
     * entry; it never evicts factual search history.
     *
     * @return true only when a new sector was recorded
     */
    public synchronized boolean markVisited(String dimension, int blockX, int blockZ) {
        return markVisited(sectorFor(dimension, blockX, blockZ));
    }

    public synchronized boolean markVisited(Sector sector) {
        Objects.requireNonNull(sector, "sector");
        if (visitedSectors.contains(sector) || isFull()) {
            return false;
        }
        requireRevisionCapacity();
        boolean added = visitedSectors.add(sector);
        if (added) {
            changed();
        }
        return added;
    }

    public synchronized List<Sector> visitedSectors() {
        return visitedSectors.stream().sorted(SECTOR_ORDER).toList();
    }

    public synchronized Optional<SurfaceAnchor> surfaceAnchor() {
        return Optional.ofNullable(surfaceAnchor);
    }

    /**
     * Returns the anchor only when it belongs to the requested dimension, preventing coordinate
     * reuse after a dimension change.
     */
    public synchronized Optional<SurfaceAnchor> surfaceAnchor(String dimension) {
        String expectedDimension = requireDimension(dimension);
        return surfaceAnchor != null && surfaceAnchor.dimension().equals(expectedDimension)
                ? Optional.of(surfaceAnchor)
                : Optional.empty();
    }

    /**
     * Records the first accepted surface position. Later calls cannot move or replace it.
     *
     * @return true only when this call established the anchor
     */
    public synchronized boolean setSurfaceAnchorIfAbsent(
            String dimension, int blockX, int blockY, int blockZ) {
        return setSurfaceAnchorIfAbsent(
                new SurfaceAnchor(dimension, blockX, blockY, blockZ));
    }

    public synchronized boolean setSurfaceAnchorIfAbsent(SurfaceAnchor candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (surfaceAnchor != null) {
            return false;
        }
        requireRevisionCapacity();
        surfaceAnchor = candidate;
        changed();
        return true;
    }

    /**
     * Encodes a canonical checkpoint. Sectors are sorted rather than emitted in discovery order,
     * so equivalent state always produces the same map and entry order.
     */
    public synchronized Map<String, String> encode() {
        List<Sector> sectors = visitedSectors.stream().sorted(SECTOR_ORDER).toList();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SCHEMA_KEY, String.valueOf(CURRENT_SCHEMA));
        values.put(KIND_KEY, KIND);
        values.put(NEXT_ORDINAL_KEY, String.valueOf(nextOrdinal));
        values.put(REVISION_KEY, String.valueOf(revision));
        values.put(VISITED_COUNT_KEY, String.valueOf(sectors.size()));
        values.put(SURFACE_ANCHOR_KEY,
                surfaceAnchor == null ? "" : encodeAnchor(surfaceAnchor));
        for (int index = 0; index < sectors.size(); index++) {
            values.put(sectorKey(index), encodeSector(sectors.get(index)));
        }
        return Collections.unmodifiableMap(values);
    }

    public record Sector(String dimension, int x, int z) {
        public Sector {
            dimension = requireDimension(dimension);
        }
    }

    public record SurfaceAnchor(String dimension, int x, int y, int z) {
        public SurfaceAnchor {
            dimension = requireDimension(dimension);
        }
    }

    private static String sectorKey(int index) {
        return SECTOR_PREFIX + String.format("%04d", index);
    }

    private static String encodeSector(Sector sector) {
        return encodeDimension(sector.dimension()) + "," + sector.x() + "," + sector.z();
    }

    private static Optional<Sector> decodeSector(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        String dimension = decodeDimension(parts[0]).orElse(null);
        if (dimension == null) {
            return Optional.empty();
        }
        return Optional.of(new Sector(
                dimension,
                parseCanonicalInt(parts[1]),
                parseCanonicalInt(parts[2])));
    }

    private static String encodeAnchor(SurfaceAnchor anchor) {
        return encodeDimension(anchor.dimension())
                + "," + anchor.x() + "," + anchor.y() + "," + anchor.z();
    }

    private static Optional<SurfaceAnchor> decodeAnchor(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        String dimension = decodeDimension(parts[0]).orElse(null);
        if (dimension == null) {
            return Optional.empty();
        }
        return Optional.of(new SurfaceAnchor(
                dimension,
                parseCanonicalInt(parts[1]),
                parseCanonicalInt(parts[2]),
                parseCanonicalInt(parts[3])));
    }

    private static String encodeDimension(String dimension) {
        return DIMENSION_ENCODER.encodeToString(
                requireDimension(dimension).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> decodeDimension(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        byte[] decoded = DIMENSION_DECODER.decode(value);
        String dimension = new String(decoded, StandardCharsets.UTF_8);
        if (!encodeDimension(dimension).equals(value)) {
            return Optional.empty();
        }
        return Optional.of(dimension);
    }

    private static String requireDimension(String dimension) {
        Identifier parsed = dimension == null ? null : Identifier.tryParse(dimension);
        if (dimension == null
                || dimension.isBlank()
                || !dimension.equals(dimension.trim())
                || dimension.length() > 256
                || dimension.chars().anyMatch(Character::isISOControl)
                || parsed == null
                || !parsed.toString().equals(dimension)) {
            throw new IllegalArgumentException("invalid dimension");
        }
        return dimension;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing hunt cursor field " + key);
        }
        return value;
    }

    private static int parseCanonicalInt(String value) {
        int parsed = Integer.parseInt(value);
        if (!String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non-canonical integer");
        }
        return parsed;
    }

    private static long parseCanonicalLong(String value) {
        long parsed = Long.parseLong(value);
        if (!String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non-canonical long");
        }
        return parsed;
    }

    private void requireRevisionCapacity() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("hunt search revision exhausted");
        }
    }

    private void changed() {
        revision++;
        dirty = true;
    }
}
