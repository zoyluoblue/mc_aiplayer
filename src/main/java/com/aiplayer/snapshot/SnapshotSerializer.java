package com.aiplayer.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class SnapshotSerializer {
    private static final Gson PRETTY_GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();
    private static final Gson COMPACT_GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private SnapshotSerializer() {
    }

    public static String toJson(WorldSnapshot snapshot) {
        return PRETTY_GSON.toJson(snapshot);
    }

    public static String toCompactJson(WorldSnapshot snapshot) {
        return COMPACT_GSON.toJson(snapshot);
    }
}
