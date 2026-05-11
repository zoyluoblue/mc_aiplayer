package com.aiplayer.agent;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.GoalChecker;
import com.aiplayer.execution.TaskSession;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class AgentCritic {
    private final GoalChecker goalChecker = new GoalChecker();

    public CritiqueResult evaluateMakeItem(
        AiPlayerEntity aiPlayer,
        String targetItem,
        int targetCount,
        TaskSession session,
        WorldSnapshot snapshot
    ) {
        int required = Math.max(1, targetCount);
        int count = aiPlayer == null ? 0 : goalChecker.countItem(aiPlayer, targetItem);
        boolean complete = aiPlayer != null && goalChecker.isComplete(aiPlayer, targetItem, required);
        Map<String, Integer> inventory = aiPlayer == null ? Map.of() : new TreeMap<>(aiPlayer.getInventorySnapshot());
        List<String> failures = session == null ? List.of() : session.getFailureHistory();
        List<String> events = session == null ? List.of() : session.getRecentEvents().stream()
            .map(event -> event.stepId() + " " + event.status() + " " + event.message())
            .limit(8)
            .toList();
        String message;
        if (complete) {
            message = "本地 Critic 判定目标已完成：" + targetItem + " " + count + "/" + required;
        } else if (session != null && session.isDone()) {
            message = "本地 Critic 判定计划已结束但目标未完成：" + targetItem + " " + count + "/" + required;
        } else {
            message = "本地 Critic 判定目标未完成：" + targetItem + " " + count + "/" + required;
        }
        return new CritiqueResult(
            complete,
            targetItem == null ? "" : targetItem,
            required,
            count,
            message,
            inventory,
            failures,
            events,
            snapshot == null ? "" : snapshot.getPlayerCommand()
        );
    }

    public record CritiqueResult(
        boolean complete,
        String targetItem,
        int targetCount,
        int currentCount,
        String message,
        Map<String, Integer> inventory,
        List<String> recentFailures,
        List<String> recentEvents,
        String snapshotReason
    ) {
        public CritiqueResult {
            inventory = inventory == null ? Map.of() : Map.copyOf(inventory);
            recentFailures = recentFailures == null ? List.of() : List.copyOf(recentFailures);
            recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
            snapshotReason = snapshotReason == null ? "" : snapshotReason;
        }
    }
}
