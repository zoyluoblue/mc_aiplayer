package com.aiplayer.agent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgentSession {
    private static final int MAX_RECENT_COMMANDS = 10;
    private String currentTaskId = "";
    private String currentGoal = "";
    private String currentTarget = "";
    private final ArrayDeque<String> recentCommands = new ArrayDeque<>();
    private final ArrayDeque<String> recentFailures = new ArrayDeque<>();
    private final Set<String> rejectedTargets = new HashSet<>();
    private final Set<String> knownChests = new HashSet<>();
    private final Set<String> knownStations = new HashSet<>();
    private final Map<String, String> miningMemory = new HashMap<>();
    private final Map<String, String> preferences = new HashMap<>();

    public void startTask(String taskId, String goal, String target) {
        currentTaskId = taskId == null ? "" : taskId;
        currentGoal = goal == null ? "" : goal;
        currentTarget = target == null ? "" : target;
        recentFailures.clear();
    }

    public void recordCommand(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        recentCommands.addLast(command);
        while (recentCommands.size() > MAX_RECENT_COMMANDS) {
            recentCommands.removeFirst();
        }
    }

    public void recordFailure(String failure) {
        if (failure == null || failure.isBlank()) {
            return;
        }
        recentFailures.addLast(failure);
        while (recentFailures.size() > MAX_RECENT_COMMANDS) {
            recentFailures.removeFirst();
        }
    }

    public void rejectTarget(String targetKey) {
        if (targetKey != null && !targetKey.isBlank()) {
            rejectedTargets.add(targetKey);
        }
    }

    public boolean hasRejectedTarget(String targetKey) {
        return rejectedTargets.contains(targetKey);
    }

    public void rememberChest(String positionKey) {
        if (positionKey != null && !positionKey.isBlank()) {
            knownChests.add(positionKey);
        }
    }

    public void rememberStation(String positionKey) {
        if (positionKey != null && !positionKey.isBlank()) {
            knownStations.add(positionKey);
        }
    }

    public void rememberMiningFact(String key, String value) {
        if (key != null && !key.isBlank()) {
            miningMemory.put(key, value == null ? "" : value);
        }
    }

    public String miningFact(String key) {
        return miningMemory.getOrDefault(key, "");
    }

    public void setPreference(String key, String value) {
        if (key != null && !key.isBlank()) {
            preferences.put(key, value == null ? "" : value);
        }
    }

    public String preference(String key) {
        return preferences.getOrDefault(key, "");
    }

    public void clearForNewDimension() {
        rejectedTargets.clear();
        knownChests.clear();
        knownStations.clear();
        miningMemory.clear();
    }

    public String summarizeForPrompt() {
        return "taskId=" + currentTaskId
            + ", goal=" + currentGoal
            + ", target=" + currentTarget
            + ", recentFailures=" + List.copyOf(recentFailures)
            + ", rejectedTargets=" + rejectedTargets
            + ", miningMemory=" + miningMemory
            + ", preferences=" + preferences;
    }

    public String getCurrentTaskId() {
        return currentTaskId;
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public String getCurrentTarget() {
        return currentTarget;
    }

    public List<String> getRecentCommands() {
        return List.copyOf(recentCommands);
    }

    public List<String> getRecentFailures() {
        return List.copyOf(recentFailures);
    }

    public Map<String, String> getMiningMemory() {
        return Map.copyOf(miningMemory);
    }
}
