package com.aiplayer.agent;

import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.execution.StepResult;

import java.util.Map;

public record StepExecutionEvent(
    String taskId,
    String stepId,
    StepExecutionStatus status,
    double progress,
    long startedAtTick,
    long endedAtTick,
    Map<String, Integer> inventoryDelta,
    String position,
    String message
) {
    public StepExecutionEvent {
        inventoryDelta = inventoryDelta == null ? Map.of() : Map.copyOf(inventoryDelta);
        taskId = taskId == null ? "task-unknown" : taskId;
        stepId = stepId == null ? "" : stepId;
        position = position == null ? "" : position;
        message = message == null ? "" : message;
    }

    public static StepExecutionEvent fromResult(
        String taskId,
        int stepIndex,
        int stepCount,
        ExecutionStep step,
        StepResult result,
        long startedAtTick,
        long endedAtTick,
        Map<String, Integer> inventoryDelta,
        String position
    ) {
        StepExecutionStatus status = statusFrom(result);
        double progress = stepCount <= 0 ? 1.0D : Math.min(1.0D, Math.max(0.0D, (stepIndex + (result.isSuccess() ? 1.0D : 0.0D)) / stepCount));
        return new StepExecutionEvent(
            taskId,
            "step-" + Math.max(1, stepIndex + 1),
            status,
            progress,
            startedAtTick,
            endedAtTick,
            inventoryDelta,
            position,
            (step == null ? "" : step.describe() + " - ") + (result == null ? "" : result.getMessage())
        );
    }

    private static StepExecutionStatus statusFrom(StepResult result) {
        if (result == null || result.isRunning()) {
            return StepExecutionStatus.RUNNING;
        }
        if (result.isSuccess()) {
            return StepExecutionStatus.SUCCESS;
        }
        return result.requiresReplan() ? StepExecutionStatus.NEED_REPLAN : StepExecutionStatus.TERMINAL_FAILURE;
    }
}
