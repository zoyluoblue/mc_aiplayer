package com.aiplayer.agent;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record AgentSkillRecord(
    String skillId,
    String goal,
    String targetItem,
    int targetCount,
    String triggerCommand,
    List<String> steps,
    List<String> evidence,
    List<String> avoidedFailures,
    String summary,
    long createdAtTick,
    int successCount,
    long lastUsedTick
) {
    public AgentSkillRecord {
        skillId = blankToDefault(skillId, "skill-unknown");
        goal = blankToDefault(goal, "make_item");
        targetItem = blankToDefault(targetItem, "");
        targetCount = Math.max(1, targetCount);
        triggerCommand = triggerCommand == null ? "" : triggerCommand;
        steps = steps == null ? List.of() : List.copyOf(steps);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        avoidedFailures = avoidedFailures == null ? List.of() : List.copyOf(avoidedFailures);
        summary = summary == null ? "" : summary;
        successCount = Math.max(1, successCount);
    }

    public boolean matches(String requestedTarget, String command) {
        String target = normalize(requestedTarget);
        String text = normalize(command);
        if (!target.isBlank() && normalize(targetItem).equals(target)) {
            return true;
        }
        return !text.isBlank()
            && (!normalize(triggerCommand).isBlank() && text.contains(normalize(triggerCommand))
                || !normalize(summary).isBlank() && normalize(summary).contains(text)
                || !normalize(targetItem).isBlank() && text.contains(normalize(targetItem)));
    }

    public AgentSkillRecord markUsed(long tick) {
        return new AgentSkillRecord(
            skillId,
            goal,
            targetItem,
            targetCount,
            triggerCommand,
            steps,
            evidence,
            avoidedFailures,
            summary,
            createdAtTick,
            successCount + 1,
            Math.max(lastUsedTick, tick)
        );
    }

    public String toPromptSummary() {
        return "{skillId=" + skillId
            + ", target=" + targetItem + " x" + targetCount
            + ", successes=" + successCount
            + ", steps=" + steps
            + ", avoidedFailures=" + avoidedFailures
            + ", summary=" + summary
            + "}";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT).trim();
    }
}
