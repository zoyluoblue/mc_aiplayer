package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public record ChatToolCall(String id, String name, String arguments) {
    public JsonObject parsedArguments() {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(arguments).getAsJsonObject();
    }
}
