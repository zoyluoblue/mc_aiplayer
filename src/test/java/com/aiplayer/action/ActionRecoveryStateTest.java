package com.aiplayer.action;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActionRecoveryStateTest {
    @Test
    void roundTripsActiveAndQueuedTasksWithTypedParameters() {
        Task active = new Task("make_item", Map.of(
            "item", "minecraft:gold_ingot",
            "quantity", 2,
            "task_id", "task-gold",
            "source_command", "帮我挖两块金锭"
        ));
        Task queued = new Task("follow", Map.of("target", "owner", "urgent", true));
        ActionRecoveryState state = new ActionRecoveryState(
            "制作 minecraft:gold_ingot",
            "task-gold",
            active,
            List.of(queued)
        );

        CompoundTag tag = state.toTag();
        ActionRecoveryState restored = ActionRecoveryState.fromTag(tag);

        assertFalse(restored.emptyState());
        assertEquals("制作 minecraft:gold_ingot", restored.currentGoal());
        assertEquals("task-gold", restored.activeTaskId());
        assertEquals("make_item", restored.activeTask().getAction());
        assertEquals("minecraft:gold_ingot", restored.activeTask().getStringParameter("item"));
        assertEquals(2, restored.activeTask().getIntParameter("quantity", 0));
        assertEquals("follow", restored.queuedTasks().getFirst().getAction());
        assertEquals("owner", restored.queuedTasks().getFirst().getStringParameter("target"));
        assertEquals(true, restored.queuedTasks().getFirst().getParameter("urgent"));
    }

    @Test
    void fingerprintIsStableAcrossParameterOrderingAndNbtRoundTrip() {
        Task active = new Task("make_item", Map.of(
            "quantity", 2,
            "task_id", "task-gold",
            "item", "minecraft:gold_ingot"
        ));
        ActionRecoveryState state = new ActionRecoveryState(
            "制作 minecraft:gold_ingot",
            "task-gold",
            active,
            List.of()
        );

        ActionRecoveryState restored = ActionRecoveryState.fromTag(state.toTag());

        assertEquals(state.fingerprint(), restored.fingerprint());
    }
}
