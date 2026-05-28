package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.MovementAction;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        registerDefaults();
    }

    public Optional<ToolDefinition> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> allTools() {
        return List.copyOf(tools.values());
    }

    private void registerDefaults() {
        register("say", "Reply to the human in chat", objectSchema()
                .property("message", stringSchema("the text to say"))
                .required("message")
                .build(), (bot, args) -> {
            String message = requiredString(args, "message");
            bot.getServer().getPlayerManager().broadcast(net.minecraft.text.Text.literal("<" + bot.getGameProfile().getName() + "> " + message), false);
            return ok("said");
        });

        register("look_at", "Turn the bot's head toward a coordinate", xyzSchema(), (bot, args) -> {
            LookAction.lookAt(bot, new Vec3d(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z")));
            return ok("looked");
        });

        register("move_to", "Walk in a straight line to a coordinate. Fails if blocked.", xyzSchema(), (bot, args) -> {
            MovementAction.startWalkTo(bot, new Vec3d(requiredInt(args, "x") + 0.5D, requiredInt(args, "y"), requiredInt(args, "z") + 0.5D));
            return ok("started");
        });

        register("mine_block", "Break the block at given coords. Bot must already be within reach.", xyzSchema(), (bot, args) -> {
            BlockPos pos = blockPos(args);
            MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            return ok("started");
        });

        register("place_block", "Place the currently held block at given coords", xyzSchema(), (bot, args) -> {
            return result(BuildAction.placeBlockAt(bot, blockPos(args)));
        });

        register("select_hotbar", "Select hotbar slot 0..8", objectSchema()
                .property("slot", integerSchema("hotbar slot", 0, 8))
                .required("slot")
                .build(), (bot, args) -> result(InventoryAction.selectHotbar(bot, requiredInt(args, "slot"))));

        register("inventory", "Get the bot's current inventory", objectSchema().build(), (bot, args) ->
                ok(InventoryAction.summarize(bot).toString()));

        register("attack_entity", "Attack a nearby entity by type", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .required("entity_type")
                .build(), (bot, args) -> {
            String entityType = requiredString(args, "entity_type");
            Identifier id = Identifier.of(entityType);
            Optional<Entity> target = bot.getServerWorld()
                    .getOtherEntities(bot, bot.getBoundingBox().expand(4.5D), entity -> Registries.ENTITY_TYPE.getId(entity.getType()).equals(id))
                    .stream()
                    .min(Comparator.comparingDouble(bot::distanceTo));
            if (target.isEmpty()) {
                return fail("no_nearby_entity: " + entityType);
            }
            return result(InteractAction.attackEntity(bot, target.get()));
        });

        register("stop", "Stop all ongoing actions", objectSchema().build(), (bot, args) -> {
            MovementAction.stopAll(bot);
            return ok("stopped");
        });
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler));
    }

    private static ToolDefinition.ToolResult result(io.github.zoyluo.aibot.action.ActionResult actionResult) {
        if (actionResult.isSuccess() || actionResult.isInProgress()) {
            return ok(actionResult.status().name().toLowerCase());
        }
        return fail(actionResult.reason());
    }

    private static ToolDefinition.ToolResult ok(String message) {
        return new ToolDefinition.ToolResult(true, message);
    }

    private static ToolDefinition.ToolResult fail(String message) {
        return new ToolDefinition.ToolResult(false, message);
    }

    private static BlockPos blockPos(JsonObject args) {
        return new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
    }

    private static int requiredInt(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsInt();
    }

    private static String requiredString(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsString();
    }

    private static JsonObject xyzSchema() {
        return objectSchema()
                .property("x", integerSchema("block x"))
                .property("y", integerSchema("block y"))
                .property("z", integerSchema("block z"))
                .required("x")
                .required("y")
                .required("z")
                .build();
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description, int min, int max) {
        JsonObject schema = integerSchema(description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private static ObjectSchemaBuilder objectSchema() {
        return new ObjectSchemaBuilder();
    }

    private static final class ObjectSchemaBuilder {
        private final JsonObject root = new JsonObject();
        private final JsonObject properties = new JsonObject();
        private final com.google.gson.JsonArray required = new com.google.gson.JsonArray();

        private ObjectSchemaBuilder() {
            root.addProperty("type", "object");
            root.add("properties", properties);
            root.add("required", required);
        }

        private ObjectSchemaBuilder property(String name, JsonObject schema) {
            properties.add(name, schema);
            return this;
        }

        private ObjectSchemaBuilder required(String name) {
            required.add(name);
            return this;
        }

        private JsonObject build() {
            return root;
        }
    }
}
