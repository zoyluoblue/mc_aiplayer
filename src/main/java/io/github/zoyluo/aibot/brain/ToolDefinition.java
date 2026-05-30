package io.github.zoyluo.aibot.brain;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;

public record ToolDefinition(
        String name,
        String description,
        JsonObject parametersSchema,
        Handler handler,
        Group group
) {
    public ToolDefinition(String name, String description, JsonObject parametersSchema, Handler handler) {
        this(name, description, parametersSchema, handler, Group.CORE);
    }

    public enum Group {
        CORE,
        MEMORY,
        COORDINATION,
        LOW_LEVEL
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult invoke(AIPlayerEntity bot, JsonObject args);
    }

    public record ToolResult(boolean ok, String message) {
        private static final Gson GSON = new Gson();

        public String toToolContent() {
            return GSON.toJson(this);
        }
    }
}
