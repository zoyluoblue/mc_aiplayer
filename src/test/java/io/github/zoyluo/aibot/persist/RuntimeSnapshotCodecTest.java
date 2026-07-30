package io.github.zoyluo.aibot.persist;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeSnapshotCodecTest {
    @Test
    void roundTripsCurrentSchema() {
        Map<String, String> checkpoint = Map.ofEntries(
                Map.entry("completed_steps", "9"),
                Map.entry("task_kind", "MINING_SERVICE"),
                Map.entry("task.schema", "1"),
                Map.entry("task.work_face", "12,-59,24"),
                Map.entry("task.phase", "RETURN_TO_FACE"),
                Map.entry("task.ores", "minecraft:deepslate_diamond_ore,minecraft:diamond_ore"),
                Map.entry("mining.schema", "1"),
                Map.entry("mining.origin", "0,-59,0"),
                Map.entry("mining.face", "12,-59,24"),
                Map.entry("mining.direction", "2"),
                Map.entry("mining.leg", "4"),
                Map.entry("mining.steps_left", "31"),
                Map.entry("mining.leg_length", "144"),
                Map.entry("mining.batches", "3"),
                Map.entry("mining.ore_fingerprint",
                        "minecraft:deepslate_diamond_ore,minecraft:diamond_ore"),
                Map.entry("capacity_parent", "auxiliary"),
                Map.entry("aux_mining.task_schema", "4"),
                Map.entry("aux_mining.batch_open", "true"),
                Map.entry("aux_mining.inventory_service_used", "true"),
                Map.entry("aux_mining.origin", "0,-59,0"),
                Map.entry("aux_mining.face", "12,-59,24"),
                Map.entry("aux_mining.budget_used", "37"),
                Map.entry("aux_mining.ore_fingerprint",
                        "minecraft:coal_ore,minecraft:deepslate_coal_ore"));
        BotRecord bot = new BotRecord(
                "MiningCodecBot", "minecraft:overworld",
                12.5D, -59.0D, 24.5D, 0.0F, 0.0F,
                "survival", 20.0F, 18, "{}", "assistant", "{}", "");
        MissionRuntimeRecord missions = new MissionRuntimeRecord(
                new MissionRecord(
                        "11111111-2222-3333-4444-555555555555",
                        new MissionSpec("mine_ore", Map.of("count", "64"),
                                List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")),
                        checkpoint),
                List.of(),
                false);
        RuntimeSnapshot original = new RuntimeSnapshot(
                RuntimeSnapshot.CURRENT_SCHEMA,
                Instant.EPOCH.toString(),
                "test-build",
                "test-session",
                List.of(new PersistedBot(bot, missions)),
                List.of());

        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader(RuntimeSnapshotCodec.encode(original)));

        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        assertEquals(original, decoded.snapshot());
        assertEquals(checkpoint, decoded.snapshot().bots().getFirst().missions().active().checkpoint());
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
