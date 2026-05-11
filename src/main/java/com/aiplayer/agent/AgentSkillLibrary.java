package com.aiplayer.agent;

import com.aiplayer.execution.ExecutionStep;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentSkillLibrary {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_SKILLS = 48;
    private static final int MAX_STEPS_PER_SKILL = 24;
    private static final int MAX_EVIDENCE_PER_SKILL = 16;
    private static final int MAX_FAILURES_PER_SKILL = 8;

    private final Map<String, AgentSkillRecord> skillsById = new LinkedHashMap<>();

    public List<AgentSkillRecord> findRelevant(String targetItem, String command, int limit, long tick) {
        if (skillsById.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<AgentSkillRecord> matches = new ArrayList<>();
        for (AgentSkillRecord skill : skillsById.values()) {
            if (skill.matches(targetItem, command)) {
                AgentSkillRecord used = skill.markUsed(tick);
                skillsById.put(skill.skillId(), used);
                matches.add(used);
            }
        }
        matches.sort(Comparator
            .comparingInt(AgentSkillRecord::successCount).reversed()
            .thenComparing(Comparator.comparingLong(AgentSkillRecord::lastUsedTick).reversed()));
        return matches.stream().limit(limit).toList();
    }

    public AgentSkillRecord rememberSuccess(
        String goal,
        String targetItem,
        int targetCount,
        String triggerCommand,
        List<ExecutionStep> planSteps,
        List<StepExecutionEvent> events,
        List<String> recentFailures,
        long tick
    ) {
        String id = skillId(goal, targetItem);
        AgentSkillRecord previous = skillsById.get(id);
        int successCount = previous == null ? 1 : previous.successCount() + 1;
        AgentSkillRecord skill = new AgentSkillRecord(
            id,
            goal,
            targetItem,
            targetCount,
            triggerCommand,
            summarizeSteps(planSteps),
            summarizeEvents(events),
            trim(recentFailures, MAX_FAILURES_PER_SKILL),
            "成功完成 " + targetItem + " x" + Math.max(1, targetCount) + "，可作为后续同类任务的高层经验",
            previous == null ? tick : previous.createdAtTick(),
            successCount,
            tick
        );
        skillsById.put(id, skill);
        trimOldest();
        return skill;
    }

    public int size() {
        return skillsById.size();
    }

    public List<AgentSkillRecord> allSkills() {
        return List.copyOf(skillsById.values());
    }

    public String toPromptSummary(String targetItem, String command, int limit, long tick) {
        List<AgentSkillRecord> relevant = findRelevant(targetItem, command, limit, tick);
        if (relevant.isEmpty()) {
            return "[]";
        }
        return GSON.toJson(relevant.stream().map(AgentSkillRecord::toPromptSummary).toList());
    }

    public String toJson() {
        return GSON.toJson(allSkills());
    }

    public void loadJson(String json) {
        skillsById.clear();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            AgentSkillRecord[] records = GSON.fromJson(json, AgentSkillRecord[].class);
            if (records == null) {
                return;
            }
            for (AgentSkillRecord record : records) {
                if (record != null) {
                    skillsById.put(record.skillId(), record);
                }
            }
            trimOldest();
        } catch (RuntimeException ignored) {
            skillsById.clear();
        }
    }

    private static String skillId(String goal, String targetItem) {
        String safeGoal = goal == null || goal.isBlank() ? "goal" : goal.replaceAll("[^a-zA-Z0-9_:-]", "_");
        String safeTarget = targetItem == null || targetItem.isBlank() ? "unknown" : targetItem.replaceAll("[^a-zA-Z0-9_:-]", "_");
        return safeGoal + ":" + safeTarget;
    }

    private static List<String> summarizeSteps(List<ExecutionStep> planSteps) {
        if (planSteps == null || planSteps.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ExecutionStep step : planSteps) {
            if (step != null) {
                result.add(step.describe());
            }
            if (result.size() >= MAX_STEPS_PER_SKILL) {
                break;
            }
        }
        return result;
    }

    private static List<String> summarizeEvents(List<StepExecutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (StepExecutionEvent event : events) {
            if (event != null) {
                result.add(event.stepId() + " " + event.status() + " " + event.message() + " delta=" + event.inventoryDelta());
            }
            if (result.size() >= MAX_EVIDENCE_PER_SKILL) {
                break;
            }
        }
        return result;
    }

    private static List<String> trim(List<String> values, int limit) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return List.of();
        }
        int from = Math.max(0, values.size() - limit);
        return List.copyOf(values.subList(from, values.size()));
    }

    private void trimOldest() {
        while (skillsById.size() > MAX_SKILLS) {
            String oldest = skillsById.values().stream()
                .min(Comparator.comparingLong(AgentSkillRecord::lastUsedTick))
                .map(AgentSkillRecord::skillId)
                .orElse(null);
            if (oldest == null) {
                return;
            }
            skillsById.remove(oldest);
        }
    }
}
