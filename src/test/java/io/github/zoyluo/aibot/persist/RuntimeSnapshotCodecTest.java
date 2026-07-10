package io.github.zoyluo.aibot.persist;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeSnapshotCodecTest {
    @Test
    void roundTripsCurrentSchema() {
        RuntimeSnapshot original = new RuntimeSnapshot(
                RuntimeSnapshot.CURRENT_SCHEMA,
                Instant.EPOCH.toString(),
                "test-build",
                "test-session",
                List.of(),
                List.of());

        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader(RuntimeSnapshotCodec.encode(original)));

        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        assertEquals(original, decoded.snapshot());
    }

    @Test
    void rejectsFutureSchemaWithoutReturningPartialState() {
        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader("{\"schemaVersion\":999,\"bots\":[],\"jobs\":[]}"));

        assertEquals(RuntimeSnapshotCodec.Status.UNSUPPORTED_SCHEMA, decoded.status());
        assertEquals(999, decoded.foundSchema());
        assertNull(decoded.snapshot());
    }

    @Test
    void rejectsMalformedOrUnversionedDocuments() {
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED,
                RuntimeSnapshotCodec.decode(new StringReader("not-json")).status());
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED,
                RuntimeSnapshotCodec.decode(new StringReader("[]")).status());
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED,
                RuntimeSnapshotCodec.decode(new StringReader("{\"bots\":[]}")).status());
    }
}
