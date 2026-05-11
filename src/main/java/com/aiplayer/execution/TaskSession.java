package com.aiplayer.execution;

import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanStep;
import com.aiplayer.snapshot.WorldSnapshot;
import com.aiplayer.agent.StepExecutionEvent;

import java.util.ArrayList;
import java.util.List;

public final class TaskSession {
    private final String goal;
    private final String targetItem;
    private final int targetCount;
    private final List<ExecutionStep> steps;
    private final List<String> failureHistory = new ArrayList<>();
    private final List<StepExecutionEvent> recentEvents = new ArrayList<>();
    private int currentStepIndex;
    private WorldSnapshot lastSnapshot;

    public TaskSession(String goal, String targetItem, int targetCount, PlanSchema plan, WorldSnapshot snapshot) {
        this.goal = goal;
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.steps = new ArrayList<>();
        for (PlanStep step : plan.getPlan()) {
            this.steps.add(new ExecutionStep(step));
        }
        this.lastSnapshot = snapshot;
    }

    public ExecutionStep currentStep() {
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            return null;
        }
        return steps.get(currentStepIndex);
    }

    public void advance() {
        currentStepIndex++;
    }

    public boolean isDone() {
        return currentStepIndex >= steps.size();
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public int getStepCount() {
        return steps.size();
    }

    public List<ExecutionStep> getSteps() {
        return List.copyOf(steps);
    }

    public String getGoal() {
        return goal;
    }

    public String getTargetItem() {
        return targetItem;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public void addFailure(String failure) {
        failureHistory.add(failure);
    }

    public int getFailureCount() {
        return failureHistory.size();
    }

    public List<String> getFailureHistory() {
        return List.copyOf(failureHistory);
    }

    public void addEvent(StepExecutionEvent event) {
        if (event == null) {
            return;
        }
        recentEvents.add(event);
        while (recentEvents.size() > 20) {
            recentEvents.remove(0);
        }
    }

    public List<StepExecutionEvent> getRecentEvents() {
        return List.copyOf(recentEvents);
    }

    public WorldSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    public void setLastSnapshot(WorldSnapshot lastSnapshot) {
        this.lastSnapshot = lastSnapshot;
    }
}
