package io.github.zoyluo.aibot.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStackTest {
    @Test
    void nestedSafetyFramesResumeInLifoOrder() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.mission(UUID.randomUUID(), "mine"));
        stack.push("combat", TaskOrigin.safety("combat"));

        assertEquals("combat", stack.popResumable(false).orElseThrow().work());
        assertEquals("mission", stack.popResumable(false).orElseThrow().work());
        assertTrue(stack.isEmpty());
    }

    @Test
    void userPauseAllowsSafetyUnwindButKeepsMissionPaused() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.mission(UUID.randomUUID(), "build"));
        stack.push("combat", TaskOrigin.safety("combat"));

        assertEquals("combat", stack.popResumable(true).orElseThrow().work());
        assertTrue(stack.popResumable(true).isEmpty());
        assertEquals(1, stack.size());
        assertEquals("mission", stack.popResumable(false).orElseThrow().work());
    }

    @Test
    void cancellationDrainsEveryFrameTopFirst() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.of(TaskOrigin.Kind.MISSION, "mission"));
        stack.push("combat", TaskOrigin.safety("combat"));
        stack.push("lava", TaskOrigin.safety("lava"));
        assertEquals(java.util.List.of("lava", "combat", "mission"),
                stack.drain().stream().map(ExecutionStack.Frame::work).toList());
    }
}
