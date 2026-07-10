package io.github.zoyluo.aibot.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.Objects;

/** Pure codec boundary so schema and corruption behavior can be tested without a Minecraft server. */
public final class RuntimeSnapshotCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RuntimeSnapshotCodec() {
    }

    public static DecodeResult decode(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return DecodeResult.malformed("runtime_root_not_object");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("schemaVersion").isNumber()) {
                return DecodeResult.malformed("runtime_schema_missing_or_invalid");
            }
            int foundSchema = root.get("schemaVersion").getAsInt();
            if (foundSchema != RuntimeSnapshot.CURRENT_SCHEMA) {
                return DecodeResult.unsupported(foundSchema);
            }
            RuntimeSnapshot snapshot = GSON.fromJson(root, RuntimeSnapshot.class);
            if (snapshot == null) {
                return DecodeResult.malformed("runtime_snapshot_null");
            }
            return DecodeResult.ok(snapshot);
        } catch (RuntimeException exception) {
            return DecodeResult.malformed(exception.getClass().getSimpleName());
        }
    }

    public static String encode(RuntimeSnapshot snapshot) {
        return GSON.toJson(Objects.requireNonNull(snapshot, "snapshot"));
    }

    public enum Status {
        OK,
        UNSUPPORTED_SCHEMA,
        MALFORMED
    }

    public record DecodeResult(Status status, RuntimeSnapshot snapshot, int foundSchema, String reason) {
        public DecodeResult {
            Objects.requireNonNull(status, "status");
            reason = reason == null ? "" : reason;
        }

        private static DecodeResult ok(RuntimeSnapshot snapshot) {
            return new DecodeResult(Status.OK, snapshot, snapshot.schemaVersion(), "");
        }

        private static DecodeResult unsupported(int schema) {
            return new DecodeResult(Status.UNSUPPORTED_SCHEMA, null, schema, "unsupported_schema");
        }

        private static DecodeResult malformed(String reason) {
            return new DecodeResult(Status.MALFORMED, null, -1, reason);
        }
    }
}
