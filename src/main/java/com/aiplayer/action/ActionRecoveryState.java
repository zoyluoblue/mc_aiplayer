package com.aiplayer.action;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ActionRecoveryState(
    String currentGoal,
    String activeTaskId,
    Task activeTask,
    List<Task> queuedTasks
) {
    private static final String TAG_CURRENT_GOAL = "CurrentGoal";
    private static final String TAG_ACTIVE_TASK_ID = "ActiveTaskId";
    private static final String TAG_ACTIVE_TASK = "ActiveTask";
    private static final String TAG_QUEUED_TASKS = "QueuedTasks";
    private static final String TAG_ACTION = "Action";
    private static final String TAG_PARAMETERS = "Parameters";
    private static final String TAG_KEY = "Key";
    private static final String TAG_TYPE = "Type";
    private static final String TAG_STRING = "StringValue";
    private static final String TAG_INT = "IntValue";
    private static final String TAG_LONG = "LongValue";
    private static final String TAG_DOUBLE = "DoubleValue";
    private static final String TAG_BOOLEAN = "BooleanValue";

    public ActionRecoveryState {
        currentGoal = currentGoal == null ? "" : currentGoal;
        activeTaskId = activeTaskId == null || activeTaskId.isBlank() ? "task-unknown" : activeTaskId;
        queuedTasks = queuedTasks == null ? List.of() : List.copyOf(queuedTasks);
    }

    public static ActionRecoveryState empty() {
        return new ActionRecoveryState("", "task-unknown", null, List.of());
    }

    public boolean emptyState() {
        return activeTask == null && queuedTasks.isEmpty() && currentGoal.isBlank();
    }

    public String fingerprint() {
        StringBuilder builder = new StringBuilder();
        builder.append(currentGoal).append('|').append(activeTaskId).append('|');
        appendTaskFingerprint(builder, activeTask);
        builder.append("|queue=");
        for (Task task : queuedTasks) {
            appendTaskFingerprint(builder, task);
            builder.append(';');
        }
        return builder.toString();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_CURRENT_GOAL, currentGoal);
        tag.putString(TAG_ACTIVE_TASK_ID, activeTaskId);
        if (activeTask != null) {
            tag.put(TAG_ACTIVE_TASK, taskToTag(activeTask));
        }
        ListTag queueTag = new ListTag();
        for (Task task : queuedTasks) {
            if (task != null) {
                queueTag.add(taskToTag(task));
            }
        }
        tag.put(TAG_QUEUED_TASKS, queueTag);
        return tag;
    }

    public static ActionRecoveryState fromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return empty();
        }
        String goal = tag.getString(TAG_CURRENT_GOAL);
        String taskId = tag.getString(TAG_ACTIVE_TASK_ID);
        Task active = tag.contains(TAG_ACTIVE_TASK) ? taskFromTag(tag.getCompound(TAG_ACTIVE_TASK)) : null;
        List<Task> queued = new ArrayList<>();
        if (tag.contains(TAG_QUEUED_TASKS)) {
            ListTag queueTag = tag.getList(TAG_QUEUED_TASKS, 10);
            for (int i = 0; i < queueTag.size(); i++) {
                Task task = taskFromTag(queueTag.getCompound(i));
                if (task != null) {
                    queued.add(task);
                }
            }
        }
        return new ActionRecoveryState(goal, taskId, active, queued);
    }

    private static CompoundTag taskToTag(Task task) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_ACTION, task.getAction());
        ListTag params = new ListTag();
        for (Map.Entry<String, Object> entry : task.getParameters().entrySet()) {
            CompoundTag param = parameterToTag(entry.getKey(), entry.getValue());
            if (param != null) {
                params.add(param);
            }
        }
        tag.put(TAG_PARAMETERS, params);
        return tag;
    }

    private static void appendTaskFingerprint(StringBuilder builder, Task task) {
        if (task == null) {
            builder.append("none");
            return;
        }
        builder.append(task.getAction()).append('{');
        TreeMap<String, Object> sorted = new TreeMap<>(task.getParameters() == null ? Map.of() : task.getParameters());
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        builder.append('}');
    }

    private static Task taskFromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        String action = tag.getString(TAG_ACTION);
        if (action == null || action.isBlank()) {
            return null;
        }
        Map<String, Object> parameters = new HashMap<>();
        if (tag.contains(TAG_PARAMETERS)) {
            ListTag params = tag.getList(TAG_PARAMETERS, 10);
            for (int i = 0; i < params.size(); i++) {
                CompoundTag param = params.getCompound(i);
                String key = param.getString(TAG_KEY);
                if (key == null || key.isBlank()) {
                    continue;
                }
                Object value = valueFromTag(param);
                if (value != null) {
                    parameters.put(key, value);
                }
            }
        }
        return new Task(action, parameters);
    }

    private static CompoundTag parameterToTag(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_KEY, key);
        if (value instanceof Integer integer) {
            tag.putString(TAG_TYPE, "int");
            tag.putInt(TAG_INT, integer);
        } else if (value instanceof Long longValue) {
            tag.putString(TAG_TYPE, "long");
            tag.putLong(TAG_LONG, longValue);
        } else if (value instanceof Float floatValue) {
            tag.putString(TAG_TYPE, "double");
            tag.putDouble(TAG_DOUBLE, floatValue.doubleValue());
        } else if (value instanceof Double doubleValue) {
            tag.putString(TAG_TYPE, "double");
            tag.putDouble(TAG_DOUBLE, doubleValue);
        } else if (value instanceof Boolean booleanValue) {
            tag.putString(TAG_TYPE, "boolean");
            tag.putBoolean(TAG_BOOLEAN, booleanValue);
        } else {
            tag.putString(TAG_TYPE, "string");
            tag.putString(TAG_STRING, value.toString());
        }
        return tag;
    }

    private static Object valueFromTag(CompoundTag tag) {
        String type = tag.getString(TAG_TYPE);
        return switch (type) {
            case "int" -> tag.getInt(TAG_INT);
            case "long" -> tag.getLong(TAG_LONG);
            case "double" -> tag.getDouble(TAG_DOUBLE);
            case "boolean" -> tag.getBoolean(TAG_BOOLEAN);
            default -> tag.getString(TAG_STRING);
        };
    }
}
