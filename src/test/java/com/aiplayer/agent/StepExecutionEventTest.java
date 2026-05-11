package com.aiplayer.agent;

import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.execution.StepResult;
import com.aiplayer.planning.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepExecutionEventTest {
    @Test
    void convertsStepResultIntoStableEvent() {
        ExecutionStep step = new ExecutionStep(PlanStep.gather("stone", "minecraft:cobblestone", 3));
        StepExecutionEvent event = StepExecutionEvent.fromResult(
            "task-1",
            0,
            3,
            step,
            StepResult.success("done"),
            10,
            30,
            Map.of("minecraft:cobblestone", 3),
            "0,64,0"
        );

        assertEquals("step-1", event.stepId());
        assertEquals(StepExecutionStatus.SUCCESS, event.status());
        assertTrue(event.progress() > 0.0D);
        assertEquals(3, event.inventoryDelta().get("minecraft:cobblestone"));
    }
}
