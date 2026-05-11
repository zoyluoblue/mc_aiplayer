package com.aiplayer.agent;

import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.planning.PlanSchema;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentContext {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String goal;
    private final String currentStep;
    private final WorldSnapshot snapshot;
    private final Map<String, Object> knownFacts;
    private final List<String> unknownFacts;
    private final List<String> recentFailures;
    private final List<String> recentEvents;
    private final List<String> allowedActions;
    private final List<String> relevantSkills;

    private AgentContext(
        String goal,
        String currentStep,
        WorldSnapshot snapshot,
        Map<String, Object> knownFacts,
        List<String> unknownFacts,
        List<String> recentFailures,
        List<String> recentEvents,
        List<String> allowedActions,
        List<String> relevantSkills
    ) {
        this.goal = goal == null ? "" : goal;
        this.currentStep = currentStep == null ? "" : currentStep;
        this.snapshot = snapshot;
        this.knownFacts = Map.copyOf(knownFacts);
        this.unknownFacts = List.copyOf(unknownFacts);
        this.recentFailures = List.copyOf(recentFailures);
        this.recentEvents = List.copyOf(recentEvents);
        this.allowedActions = List.copyOf(allowedActions);
        this.relevantSkills = List.copyOf(relevantSkills);
    }

    public static AgentContext from(
        WorldSnapshot snapshot,
        PlanSchema plan,
        ExecutionStep currentStep,
        List<String> recentFailures,
        List<String> recentEvents,
        ActionManifest manifest
    ) {
        return from(snapshot, plan, currentStep, recentFailures, recentEvents, manifest, List.of());
    }

    public static AgentContext from(
        WorldSnapshot snapshot,
        PlanSchema plan,
        ExecutionStep currentStep,
        List<String> recentFailures,
        List<String> recentEvents,
        ActionManifest manifest,
        List<String> relevantSkills
    ) {
        WorldSnapshot safeSnapshot = snapshot == null ? WorldSnapshot.empty("") : snapshot;
        ActionManifest safeManifest = manifest == null ? ActionManifest.survivalDefaults() : manifest;
        Map<String, Object> knownFacts = new LinkedHashMap<>();
        knownFacts.put("availableItems", safeSnapshot.availableItems());
        knownFacts.put("nearbyChestCount", safeSnapshot.getNearbyChests().size());
        knownFacts.put("nearbyBlocks", safeSnapshot.getNearbyBlocks().stream().map(WorldSnapshot.BlockSnapshot::getBlock).distinct().toList());
        knownFacts.put("nearbyEntities", safeSnapshot.getNearbyEntities().stream().map(WorldSnapshot.EntitySnapshot::getType).distinct().toList());

        List<String> unknownFacts = List.of(
            "未扫描范围外的箱子内容未知",
            "地下未暴露矿物需要实际探矿后确认",
            "路径可达性可能因地形变化失效"
        );

        return new AgentContext(
            plan == null ? "" : plan.getGoal(),
            currentStep == null ? "" : currentStep.describe(),
            safeSnapshot,
            knownFacts,
            unknownFacts,
            recentFailures == null ? List.of() : recentFailures,
            recentEvents == null ? List.of() : recentEvents,
            safeManifest.names().stream().sorted().toList(),
            relevantSkills == null ? List.of() : relevantSkills
        );
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String snapshotJson() {
        return SnapshotSerializer.toCompactJson(snapshot);
    }

    public String getGoal() {
        return goal;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public Map<String, Object> getKnownFacts() {
        return knownFacts;
    }

    public List<String> getUnknownFacts() {
        return unknownFacts;
    }

    public List<String> getRecentFailures() {
        return recentFailures;
    }

    public List<String> getRecentEvents() {
        return recentEvents;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public List<String> getRelevantSkills() {
        return relevantSkills;
    }
}
