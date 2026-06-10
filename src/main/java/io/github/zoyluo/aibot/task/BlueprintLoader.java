package io.github.zoyluo.aibot.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BlueprintLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_EXPANDED_BLOCKS = 4096;

    private BlueprintLoader() {
    }

    public static BlueprintSchema load(String name) throws IOException {
        // P3 参数化蓝图:名字编码 "custom:宽x深x高:材质"(如 custom:7x5x4:stone)直接生成,不读文件。
        // 走名字通道的好处:Goal.Build/GoalStep.tag/规划器/执行器零改动,队列与备料链天然适用。
        if (name != null && name.startsWith("custom:")) {
            BlueprintSchema custom = BlueprintSchema.parametricHouse(name);
            if (custom == null) {
                throw new IOException("blueprint_bad_custom_spec: " + name + " (expect custom:WxDxH:material)");
            }
            return expand(custom);
        }
        if ("hut_5x5".equals(name) || "small_hut".equals(name)) {
            ensureDefaultBlueprintsWritten();
        }
        Path path = blueprintDir().resolve(name + ".json");
        if (!Files.exists(path)) {
            throw new IOException("blueprint_not_found: " + name);
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            BlueprintSchema schema = GSON.fromJson(reader, BlueprintSchema.class);
            if (schema == null) {
                throw new IOException("blueprint_empty: " + name);
            }
            BlueprintSchema expanded = expand(schema);
            if (expanded.placements() == null || expanded.placements().isEmpty()) {
                throw new IOException("blueprint_empty: " + name);
            }
            return expanded;
        }
    }

    public static BlueprintSchema expand(BlueprintSchema schema) throws IOException {
        Map<Key, BlueprintSchema.BlockPlacement> placements = new LinkedHashMap<>();
        if (schema.ops() != null) {
            for (BlueprintSchema.Op op : schema.ops()) {
                for (BlueprintSchema.BlockPlacement placement : expandOp(op)) {
                    put(placements, placement);
                }
            }
        }
        if (schema.placements() != null) {
            for (BlueprintSchema.BlockPlacement placement : schema.placements()) {
                put(placements, placement);
            }
        }
        if (placements.size() > MAX_EXPANDED_BLOCKS) {
            throw new IOException("blueprint_too_large: " + placements.size());
        }
        List<BlueprintSchema.BlockPlacement> sorted = new ArrayList<>(placements.values());
        sorted.sort(Comparator
                .comparingInt(BlueprintSchema.BlockPlacement::dy)
                .thenComparingInt(BlueprintSchema.BlockPlacement::dx)
                .thenComparingInt(BlueprintSchema.BlockPlacement::dz));
        return new BlueprintSchema(
                schema.name(),
                schema.width(),
                schema.height(),
                schema.depth(),
                List.copyOf(sorted),
                List.of());
    }

    private static void put(Map<Key, BlueprintSchema.BlockPlacement> placements, BlueprintSchema.BlockPlacement placement) {
        Key key = new Key(placement.dx(), placement.dy(), placement.dz());
        placements.remove(key);
        placements.put(key, placement);
    }

    private static List<BlueprintSchema.BlockPlacement> expandOp(BlueprintSchema.Op op) throws IOException {
        if (op.type() == null || op.from() == null || op.to() == null || op.from().length < 3 || op.to().length < 3) {
            throw new IOException("bad_blueprint_op");
        }
        int minX = Math.min(op.from()[0], op.to()[0]);
        int minY = Math.min(op.from()[1], op.to()[1]);
        int minZ = Math.min(op.from()[2], op.to()[2]);
        int maxX = Math.max(op.from()[0], op.to()[0]);
        int maxY = Math.max(op.from()[1], op.to()[1]);
        int maxZ = Math.max(op.from()[2], op.to()[2]);
        String block = op.block() == null || op.block().isBlank() ? fallbackBlock(op.palette()) : op.block();
        List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!includes(op.type(), x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
                        continue;
                    }
                    placements.add(new BlueprintSchema.BlockPlacement(x, y, z, block, op.palette()));
                }
            }
        }
        return placements;
    }

    private static boolean includes(String type, int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws IOException {
        return switch (type) {
            case "box", "fill", "layer" -> true;
            case "hollow_box" -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
            default -> throw new IOException("unknown_blueprint_op: " + type);
        };
    }

    private static String fallbackBlock(String palette) {
        if (palette == null || palette.isBlank()) {
            return "minecraft:air";
        }
        return switch (palette) {
            case "planks" -> "minecraft:oak_planks";
            case "logs" -> "minecraft:oak_log";
            case "stone_like" -> "minecraft:cobblestone";
            case "dirt_like" -> "minecraft:dirt";
            case "glass" -> "minecraft:glass";
            default -> "minecraft:air";
        };
    }

    private static void ensureDefaultBlueprintsWritten() throws IOException {
        writeIfMissing("hut_5x5.json", BlueprintSchema.hut5x5());
        writeIfMissing("small_hut.json", BlueprintSchema.smallHutOps());
    }

    private static void writeIfMissing(String fileName, BlueprintSchema schema) throws IOException {
        Path path = blueprintDir().resolve(fileName);
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(schema, writer);
            }
        }
    }

    private static Path blueprintDir() {
        return FabricLoader.getInstance().getGameDir().resolve("blueprints");
    }

    private record Key(int x, int y, int z) {
    }
}
