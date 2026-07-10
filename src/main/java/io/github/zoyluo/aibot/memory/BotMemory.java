package io.github.zoyluo.aibot.memory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BotMemory {
    private static final int FACT_INJECT_LIMIT = 8;
    private static final int PLACE_INJECT_LIMIT = 10;

    private final Map<String, String> facts = new LinkedHashMap<>();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Deque<String> goalSteps = new ArrayDeque<>();
    private int goalCursor;
    private String goalTitle = "";

    public void remember(String key, String value) {
        facts.put(cleanKey(key), value == null ? "" : value.trim());
    }

    public Optional<String> recall(String key) {
        return Optional.ofNullable(facts.get(cleanKey(key)));
    }

    public boolean forget(String key) {
        return facts.remove(cleanKey(key)) != null;
    }

    public void markPlace(String name, ServerWorld world, BlockPos pos) {
        places.put(cleanKey(name), new Place(world.getRegistryKey().getValue().toString(), pos.toImmutable()));
    }

    public Optional<Place> place(String name) {
        return Optional.ofNullable(places.get(cleanKey(name)));
    }

    public Optional<BlockPos> placeIn(ServerWorld world, String... names) {
        String dimension = world.getRegistryKey().getValue().toString();
        for (String name : names) {
            Place place = places.get(cleanKey(name));
            if (place != null && dimension.equals(place.dimension())) {
                return Optional.of(place.pos());
            }
        }
        return Optional.empty();
    }

    public void setGoal(String title, Iterable<String> steps) {
        goalTitle = title == null ? "" : title.trim();
        goalSteps.clear();
        for (String step : steps) {
            if (step != null && !step.isBlank()) {
                goalSteps.addLast(step.trim());
            }
        }
        goalCursor = 0;
    }

    public boolean clearGoal() {
        boolean changed = !goalTitle.isBlank() || !goalSteps.isEmpty() || goalCursor != 0;
        goalTitle = "";
        goalSteps.clear();
        goalCursor = 0;
        return changed;
    }

    public String advanceGoal(String result) {
        if (goalSteps.isEmpty()) {
            return "no_goal";
        }
        goalCursor = Math.min(goalCursor + 1, goalSteps.size());
        return goalStatus(result);
    }

    public String goalStatus(String lastResult) {
        if (goalSteps.isEmpty()) {
            return "No active long-term goal.";
        }
        String current = currentGoalStep().orElse("complete");
        String suffix = lastResult == null || lastResult.isBlank() ? "" : " last_result=" + lastResult.trim();
        return "Goal '" + goalTitle + "' step " + Math.min(goalCursor + 1, goalSteps.size()) + "/" + goalSteps.size()
                + ": " + current + suffix;
    }

    public boolean hasActiveGoal() {
        return currentGoalStep().isPresent();
    }

    public String goalTitle() {
        return goalTitle;
    }

    public int goalCurrentStepIndex() {
        return goalSteps.isEmpty() ? 0 : Math.min(goalCursor, goalSteps.size());
    }

    public int goalTotalSteps() {
        return goalSteps.size();
    }

    public List<String> goalSteps() {
        return List.copyOf(new ArrayList<>(goalSteps));
    }

    public String goalDriveStatus(String lastResult) {
        if (goalSteps.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Goal: ").append(goalTitle.isBlank() ? "(untitled)" : goalTitle).append("\n");
        builder.append("- progress: ").append(Math.min(goalCursor, goalSteps.size())).append("/").append(goalSteps.size()).append(" completed\n");
        builder.append("- current_step: ").append(currentGoalStep().orElse("complete")).append("\n");
        int index = 0;
        int remaining = 0;
        for (String step : goalSteps) {
            if (index++ >= goalCursor) {
                remaining++;
            }
        }
        builder.append("- remaining_steps: ").append(remaining).append("\n");
        if (lastResult != null && !lastResult.isBlank()) {
            builder.append("- last_result: ").append(lastResult.trim()).append("\n");
        }
        return builder.toString().trim();
    }

    public Optional<String> currentGoalStep() {
        if (goalCursor < 0 || goalCursor >= goalSteps.size()) {
            return Optional.empty();
        }
        int index = 0;
        for (String step : goalSteps) {
            if (index == goalCursor) {
                return Optional.of(step);
            }
            index++;
        }
        return Optional.empty();
    }

    public String inject() {
        StringBuilder builder = new StringBuilder();
        if (!places.isEmpty()) {
            builder.append("Known places:\n");
            int count = 0;
            for (Map.Entry<String, Place> entry : places.entrySet()) {
                if (count++ >= PLACE_INJECT_LIMIT) {
                    break;
                }
                Place place = entry.getValue();
                builder.append("- ").append(entry.getKey()).append(" = ")
                        .append(place.dimension()).append(" ")
                        .append(place.pos().getX()).append(",")
                        .append(place.pos().getY()).append(",")
                        .append(place.pos().getZ()).append("\n");
            }
        }
        if (!goalSteps.isEmpty()) {
            builder.append("Long-term goal:\n").append(goalDriveStatus("")).append("\n");
        }
        if (!facts.isEmpty()) {
            builder.append("Remembered facts:\n");
            int start = Math.max(0, facts.size() - FACT_INJECT_LIMIT);
            int index = 0;
            for (Map.Entry<String, String> entry : facts.entrySet()) {
                if (index++ < start) {
                    continue;
                }
                builder.append("- ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtCompound factNbt = new NbtCompound();
        facts.forEach(factNbt::putString);
        root.put("facts", factNbt);
        NbtCompound placeNbt = new NbtCompound();
        for (Map.Entry<String, Place> entry : places.entrySet()) {
            NbtCompound place = new NbtCompound();
            place.putString("dimension", entry.getValue().dimension());
            place.putInt("x", entry.getValue().pos().getX());
            place.putInt("y", entry.getValue().pos().getY());
            place.putInt("z", entry.getValue().pos().getZ());
            placeNbt.put(entry.getKey(), place);
        }
        root.put("places", placeNbt);
        root.putString("goalTitle", goalTitle);
        root.putInt("goalCursor", goalCursor);
        NbtList steps = new NbtList();
        goalSteps.forEach(step -> steps.add(NbtString.of(step)));
        root.put("goalSteps", steps);
        return root;
    }

    public void load(NbtCompound root) {
        facts.clear();
        places.clear();
        goalSteps.clear();
        goalCursor = 0;
        goalTitle = "";
        NbtCompound factNbt = root.getCompound("facts");
        for (String key : factNbt.getKeys()) {
            facts.put(key, factNbt.getString(key));
        }
        NbtCompound placeNbt = root.getCompound("places");
        for (String key : placeNbt.getKeys()) {
            NbtCompound place = placeNbt.getCompound(key);
            places.put(key, new Place(
                    place.getString("dimension"),
                    new BlockPos(place.getInt("x"), place.getInt("y"), place.getInt("z"))));
        }
        goalTitle = root.getString("goalTitle");
        goalCursor = Math.max(0, root.getInt("goalCursor"));
        NbtList steps = root.getList("goalSteps", net.minecraft.nbt.NbtElement.STRING_TYPE);
        for (int index = 0; index < steps.size(); index++) {
            goalSteps.addLast(steps.getString(index));
        }
        goalCursor = Math.min(goalCursor, goalSteps.size());
    }

    private static String cleanKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("missing_memory_key");
        }
        return key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record Place(String dimension, BlockPos pos) {
    }
}
