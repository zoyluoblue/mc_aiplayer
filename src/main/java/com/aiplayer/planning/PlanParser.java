package com.aiplayer.planning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.Optional;

public final class PlanParser {
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();

    private PlanParser() {
    }

    public static Optional<PlanSchema> parse(String json) {
        try {
            return Optional.ofNullable(GSON.fromJson(json, PlanSchema.class));
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    public static String toJson(PlanSchema plan) {
        return GSON.toJson(plan);
    }
}
