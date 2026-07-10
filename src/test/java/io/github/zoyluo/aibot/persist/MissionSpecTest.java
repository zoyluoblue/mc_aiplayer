package io.github.zoyluo.aibot.persist;

import io.github.zoyluo.aibot.goal.Goal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionSpecTest {
    @Test
    void registryIndependentGoalKindsRoundTripWithoutTaskState() {
        List<Goal> goals = List.of(
                new Goal.HavePickaxeTier(3),
                new Goal.Armor(),
                new Goal.Workstation(),
                new Goal.Food(5),
                new Goal.Build("small_hut"));

        for (Goal goal : goals) {
            MissionSpec spec = MissionSpec.fromGoal(goal);
            assertEquals(goal, spec.toGoal().orElseThrow());
            assertTrue(spec.params().keySet().stream().noneMatch(key -> key.contains("task") || key.contains("phase")));
        }
    }

    @Test
    void invalidNumericOrFutureTypeIsIsolated() {
        assertTrue(new MissionSpec("food", java.util.Map.of("count", "not-a-number"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("future_goal", java.util.Map.of(), List.of()).toGoal().isEmpty());
    }
}
